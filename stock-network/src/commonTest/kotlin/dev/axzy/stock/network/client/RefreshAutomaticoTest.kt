package dev.axzy.stock.network.client

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.network.FIXED_NOW
import dev.axzy.stock.network.StockApi
import dev.axzy.stock.network.motorSimulado
import dev.axzy.stock.network.testConfig
import dev.axzy.stock.network.tokenStoreWithSession
import dev.axzy.stock.network.error.ApiError
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REFRESH AUTOMÁTICO ANTE `401`
 *
 * Las tres garantías que se prueban aquí, y por qué cada una importa:
 *
 *  1. **Reintenta UNA vez.** Un cliente que reintenta sin límite convierte un
 *     servidor caído en una tormenta de peticiones.
 *  2. **No entra en bucle.** El reintento va por un camino que no puede
 *     refrescar, así que el bucle es imposible por construcción; el test lo
 *     fija para que nadie lo rompa al refactorizar.
 *  3. **`TOKEN_REUSED` no es un `401` cualquiera.** Significa que la sesión está
 *     comprometida y hay que descartarla y volver a pedir credenciales.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class RefreshAutomaticoTest {

    private val loginJson = """
        {
          "accessToken": "access-1", "refreshToken": "refresh-2", "expiresIn": 900,
          "user": { "id": 7, "username": "jperez", "name": "Juan Pérez" }
        }
    """.trimIndent()

    private fun MockRequestHandleScope.ok(cuerpo: String) = respond(
        content = cuerpo,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
    )

    private fun MockRequestHandleScope.noAutorizado(cuerpo: String = """{ "code": "UNAUTHENTICATED", "message": "venció" }""") = respond(
        content = cuerpo,
        status = HttpStatusCode.Unauthorized,
        headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
    )

    @Test
    fun `un 401 dispara un refresh y un unico reintento`() = runTest {
        var productosLlamados = 0
        val (engine, grabado) = motorSimulado { request ->
            when (request.url.encodedPath) {
                "/v1/products" -> {
                    productosLlamados++
                    // El primero falla con 401; el reintento, ya con el token
                    // nuevo, funciona.
                    if (productosLlamados == 1) noAutorizado()
                    else ok("""{ "items": [], "page": 0, "size": 50, "totalItems": 0, "totalPages": 0 }""")
                }

                "/v1/auth/refresh" -> ok(loginJson)
                else -> error("ruta inesperada: ${request.url.encodedPath}")
            }
        }
        val tokens = tokenStoreWithSession()
        val api = StockApi(config = testConfig(), tokens = tokens, engine = engine)

        val resultado = api.products.list()

        assertIs<Either.Right<*>>(resultado)
        assertEquals(2, productosLlamados)
        assertEquals(1, grabado.vecesRuta("/v1/auth/refresh"))

        // La rotación se persiste: el refresh token nuevo es el que se guarda.
        val guardada = assertNotNull(tokens.load())
        assertEquals("access-1", guardada.accessToken)
        assertEquals("refresh-2", guardada.refreshToken)
        // El instante de caducidad se calcula con el reloj del cliente.
        assertEquals(FIXED_NOW + 900, guardada.expiresAtEpochSeconds)
    }

    @Test
    fun `el reintento lleva el token nuevo`() = runTest {
        var llamado = 0
        val (engine, grabado) = motorSimulado { request ->
            when (request.url.encodedPath) {
                "/v1/products" -> {
                    llamado++
                    if (llamado == 1) noAutorizado() else ok("""{ "items": [] }""")
                }

                "/v1/auth/refresh" -> ok(loginJson)
                else -> error("ruta inesperada")
            }
        }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        api.products.list()

        val reintento = assertNotNull(grabado.ultimaPara("/v1/products"))
        assertEquals("Bearer access-1", reintento.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `si el reintento vuelve a fallar con 401 no se refresca una segunda vez`() = runTest {
        val (engine, grabado) = motorSimulado { request ->
            when (request.url.encodedPath) {
                // Siempre 401, incluso con el token recién refrescado: es el caso
                // que en un cliente mal hecho produce un bucle infinito.
                "/v1/products" -> noAutorizado()
                "/v1/auth/refresh" -> ok(loginJson)
                else -> error("ruta inesperada")
            }
        }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        val resultado = api.products.list()

        assertIs<ApiError.Unauthenticated>(assertIs<Either.Left<ApiError>>(resultado).error)
        // Una sola rotación y dos intentos: ni bucle ni tormenta de peticiones.
        assertEquals(1, grabado.vecesRuta("/v1/auth/refresh"))
        assertEquals(2, grabado.vecesRuta("/v1/products"))
    }

    @Test
    fun `un TOKEN_REUSED descarta la sesion y avisa a la app`() = runTest {
        val (engine, grabado) = motorSimulado { request ->
            when (request.url.encodedPath) {
                "/v1/products" -> noAutorizado()
                "/v1/auth/refresh" -> respond(
                    content = """{ "code": "TOKEN_REUSED", "message": "sesión comprometida" }""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )

                else -> error("ruta inesperada")
            }
        }
        val tokens = tokenStoreWithSession()
        var avisada = false
        val api = StockApi(config = testConfig(), tokens = tokens, engine = engine)
        api.onSessionExpired = { avisada = true }

        val resultado = api.products.list()

        // Se devuelve el error del REFRESH, no el 401 original: «tu sesión se
        // cerró por seguridad» es lo accionable; «venció» no lo es.
        assertIs<ApiError.TokenReused>(assertIs<Either.Left<ApiError>>(resultado).error)
        assertNull(tokens.load())
        assertTrue(avisada)
        // No se reintenta: la familia entera está revocada.
        assertEquals(1, grabado.vecesRuta("/v1/products"))
    }

    @Test
    fun `un fallo de red durante el refresh no cierra la sesion`() = runTest {
        val (engine, _) = motorSimulado { request ->
            when (request.url.encodedPath) {
                "/v1/products" -> noAutorizado()
                // Sin cobertura en el piso del almacén: la sesión puede seguir
                // siendo válida, y cerrarla dejaría al operador fuera de la app.
                "/v1/auth/refresh" -> throw IllegalStateException("sin ruta al host")
                else -> error("ruta inesperada")
            }
        }
        val tokens = tokenStoreWithSession()
        var avisada = false
        val api = StockApi(config = testConfig(), tokens = tokens, engine = engine)
        api.onSessionExpired = { avisada = true }

        val resultado = api.products.list()

        assertIs<ApiError.Unauthenticated>(assertIs<Either.Left<ApiError>>(resultado).error)
        assertNotNull(tokens.load())
        assertTrue(!avisada)
    }

    @Test
    fun `sin refresh token no se intenta refrescar`() = runTest {
        val (engine, grabado) = motorSimulado { request ->
            when (request.url.encodedPath) {
                "/v1/products" -> noAutorizado()
                else -> error("no debería llamarse a ${request.url.encodedPath}")
            }
        }
        // Sin sesión guardada: no hay nada que rotar.
        val api = StockApi(config = testConfig(), tokens = InMemoryTokenStore(null), engine = engine)

        val resultado = api.products.list()

        assertIs<ApiError.Unauthenticated>(assertIs<Either.Left<ApiError>>(resultado).error)
        assertEquals(0, grabado.vecesRuta("/v1/auth/refresh"))
    }

    @Test
    fun `el login no intenta refrescar aunque el servidor conteste 401`() = runTest {
        val (engine, grabado) = motorSimulado { request ->
            when (request.url.encodedPath) {
                "/v1/auth/login" -> respond(
                    content = """{ "code": "INVALID_CREDENTIALS", "message": "usuario o contraseña incorrectos" }""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
                )

                else -> error("no debería llamarse a ${request.url.encodedPath}")
            }
        }
        val api = StockApi(config = testConfig(), tokens = InMemoryTokenStore(null), engine = engine)

        val resultado = api.auth.login("jperez", "mal")

        assertIs<ApiError.InvalidCredentials>(assertIs<Either.Left<ApiError>>(resultado).error)
        assertEquals(0, grabado.vecesRuta("/v1/auth/refresh"))
    }

    @Test
    fun `el login guarda la sesion para que el refresh automatico pueda usarla`() = runTest {
        val (engine, _) = motorSimulado { ok(loginJson) }
        val tokens = InMemoryTokenStore(null)
        val api = StockApi(config = testConfig(), tokens = tokens, engine = engine)

        val resultado = api.auth.login("jperez", "bien")

        assertIs<Either.Right<*>>(resultado)
        val guardada = assertNotNull(tokens.load())
        assertEquals("access-1", guardada.accessToken)
        assertEquals(false, guardada.isExpired(FIXED_NOW))
        // A los 15 minutos justos ya no sirve, y con el margen de 30 segundos
        // tampoco treinta segundos antes.
        assertTrue(guardada.isExpired(FIXED_NOW + 880))
        assertTrue(guardada.isExpired(FIXED_NOW + 900))
    }

    @Test
    fun `un 403 no dispara refresh porque refrescar no arregla un permiso`() = runTest {
        val (engine, grabado) = motorSimulado {
            respond(
                content = """{ "code": "PERMISSION_DENIED", "message": "no tienes el permiso" }""",
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        val resultado = api.products.list()

        assertIs<ApiError.PermissionDenied>(assertIs<Either.Left<ApiError>>(resultado).error)
        assertEquals(0, grabado.vecesRuta("/v1/auth/refresh"))
    }
}
