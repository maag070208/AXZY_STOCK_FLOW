package dev.axzy.stock.network.client

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.network.MotorGrabado
import dev.axzy.stock.network.StockApi
import dev.axzy.stock.network.motorSimulado
import dev.axzy.stock.network.testConfig
import dev.axzy.stock.network.tokenStoreWithSession
import dev.axzy.stock.network.dto.common.ErrorDto
import dev.axzy.stock.network.error.ApiError
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL CONTRATO DE ERROR
 *
 * Lo que se comprueba aquí, y por qué importa:
 *
 *  1. **Cada `code` del contrato tiene su caso tipado.** Un `403` genérico
 *     obligaría al usuario a adivinar si tiene que pedir un permiso, esperar o
 *     llamar a otra persona.
 *  2. **La decisión se toma con `code`, jamás con `message`.** Hay un test que
 *     cambia el texto del mensaje y comprueba que el caso tipado no cambia: es
 *     la garantía de que traducir un mensaje no rompe al cliente.
 *  3. Un `5xx` sin cuerpo de error sigue siendo un error clasificado, no una
 *     «respuesta malformada».
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ErroresDelContratoTest {

    private fun error(
        code: String,
        message: String = "mensaje",
        details: String? = null,
        httpStatus: Int = 400,
    ): ApiError =
        ApiError.from(
            dto = ErrorDto(
                code = code,
                message = message,
                details = details?.let { Json.decodeFromString<JsonObject>(it) },
            ),
            httpStatus = httpStatus,
            requestId = "req-1",
        )

    @Test
    fun `los cinco codigos de autorizacion son cinco casos distintos`() {
        assertIs<ApiError.PermissionDenied>(error("PERMISSION_DENIED"))
        assertIs<ApiError.ScopeInsufficient>(error("SCOPE_INSUFFICIENT"))
        assertIs<ApiError.PolicyDenied>(error("POLICY_DENIED"))
        assertIs<ApiError.SegregationOfDuties>(error("SEGREGATION_OF_DUTIES"))
        assertIs<ApiError.Unauthenticated>(error("UNAUTHENTICATED"))
    }

    @Test
    fun `los codigos de sesion se distinguen entre si`() {
        assertIs<ApiError.InvalidCredentials>(error("INVALID_CREDENTIALS"))
        assertIs<ApiError.AccountDisabled>(error("ACCOUNT_DISABLED"))
        // `TOKEN_REUSED` no es `INVALID_CREDENTIALS`: la interfaz tiene que decir
        // «tu sesión se cerró por seguridad», no «contraseña incorrecta».
        assertIs<ApiError.TokenReused>(error("TOKEN_REUSED"))
    }

    @Test
    fun `el bloqueo de cuenta expone hasta cuando`() {
        val bloqueada = error(
            code = "ACCOUNT_LOCKED",
            details = """{ "until": "2026-10-07T22:00:00Z" }""",
        )
        val caso = assertIs<ApiError.AccountLocked>(bloqueada)
        assertEquals("2026-10-07T22:00:00Z", caso.until)
    }

    @Test
    fun `el stock insuficiente trae la cantidad disponible para ofrecer el faltante`() {
        val error = error(
            code = "INSUFFICIENT_STOCK",
            details = """{ "available": 80.000, "requested": 100.000, "location": "04-B-C" }""",
        )
        val caso = assertIs<ApiError.InsufficientStock>(error)
        assertEquals(80.0, caso.available)
        assertEquals(100.0, caso.requested)
        assertEquals("04-B-C", caso.location)
    }

    @Test
    fun `el conflicto de estado conserva su codigo concreto`() {
        val conflicto = ApiError.from(
            ErrorDto(code = "PRODUCT_HAS_STOCK", message = "tiene stock", details = null),
            httpStatus = 409,
        )
        val caso = assertIs<ApiError.Conflict>(conflicto)
        // Colapsarlo en «conflicto» obligaría a la UI a leer el mensaje, que es
        // justo lo que el contrato prohíbe.
        assertEquals("PRODUCT_HAS_STOCK", caso.code)
    }

    @Test
    fun `una discrepancia sin aprobar es un conflicto con su codigo`() {
        val caso = assertIs<ApiError.Conflict>(
            ApiError.from(ErrorDto(code = "DISCREPANCY_NOT_APPROVED"), httpStatus = 409)
        )
        assertEquals("DISCREPANCY_NOT_APPROVED", caso.code)
    }

    @Test
    fun `un 422 de negocio se clasifica como validacion con los campos`() {
        val fallo = ApiError.from(
            ErrorDto(
                code = "REASON_REQUIRED",
                message = "falta el motivo",
                details = Json.decodeFromString<JsonObject>("""{ "field": "motivo" }"""),
            ),
            httpStatus = 422,
        )
        val caso = assertIs<ApiError.Validation>(fallo)
        assertEquals("REASON_REQUIRED", caso.code)
        assertEquals("motivo", caso.fieldErrors["field"])
    }

    @Test
    fun `solo el 429 y los fallos de infraestructura son reintentables`() {
        assertTrue(error("RATE_LIMITED").isRetryable)
        assertTrue(
            ApiError.fromStatus(503, requestId = null).isRetryable
        )
        assertTrue(ApiError.Network().isRetryable)

        // Reintentar una segregación de funciones no la resuelve: hay que
        // cambiar de persona.
        assertTrue(!error("SEGREGATION_OF_DUTIES").isRetryable)
        assertTrue(!error("PERMISSION_DENIED").isRetryable)
    }

    @Test
    fun `un codigo desconocido no rompe al cliente desplegado`() {
        // El servidor puede añadir códigos sin que las apps ya instaladas dejen
        // de funcionar: no se lanza nada y el código se conserva.
        val futuro = error("CODIGO_QUE_NO_EXISTE_TODAVIA", message = "algo nuevo", httpStatus = 422)
        assertIs<ApiError.Validation>(futuro)
        assertEquals("CODIGO_QUE_NO_EXISTE_TODAVIA", futuro.code)

        // Y si el estado HTTP tampoco dice nada reconocible, queda como
        // desconocido en lugar de disfrazarse de otra cosa.
        val raro = error("CODIGO_RARO", httpStatus = 418)
        assertIs<ApiError.Unknown>(raro)
        assertEquals("CODIGO_RARO", raro.code)
    }

    @Test
    fun `la decision se toma con el codigo y nunca con el mensaje`() {
        val primero = error("POLICY_DENIED", message = "No puedes aprobar tu propio conteo")
        val segundo = error("POLICY_DENIED", message = "⚠ 403 - forbidden (policy)")
        val tercero = error("POLICY_DENIED", message = "")

        // Los tres son el MISMO caso tipado aunque el texto no se parezca en
        // nada: es lo que permite traducir y reescribir mensajes sin romper al
        // cliente.
        assertEquals(primero::class, segundo::class)
        assertEquals(primero::class, tercero::class)
        assertEquals("POLICY_DENIED", (primero as ApiError).code)
    }

    @Test
    fun `una respuesta 5xx sin cuerpo de error se clasifica por su estado`() {
        val caida = ApiError.fromStatus(503, message = "<html>bad gateway</html>", requestId = "req-9")
        assertIs<ApiError.ServerUnavailable>(caida)
        assertEquals(503, caida.httpStatus)
        assertEquals("req-9", caida.requestId)
    }

    @Test
    fun `un 401 sin cuerpo sigue siendo falta de identidad`() {
        assertIs<ApiError.Unauthenticated>(ApiError.fromStatus(401, requestId = null))
    }

    @Test
    fun `un error de red no lleva codigo del contrato porque el servidor no contesto`() {
        val red = ApiError.Network(cause = IllegalStateException("sin ruta al host"))
        assertEquals("NETWORK", red.code)
        assertNull(red.details)
        assertTrue(red.isRetryable)
    }
}

/**
 * El mismo contrato, pero **por el camino real del cliente**: motor simulado,
 * respuesta HTTP y traducción. Comprueba lo que un test unitario de `ApiError`
 * no puede comprobar: que el estado HTTP y el `X-Request-Id` de la respuesta
 * llegan al error.
 */
class ErroresPorElClienteTest {

    private fun body(code: String, message: String = "no") =
        """{ "code": "$code", "message": "$message", "details": { "available": 3.500 } }"""

    private fun apiQueResponde(
        status: HttpStatusCode,
        cuerpo: String,
    ): Pair<StockApi, MotorGrabado> {
        val (engine, grabado) = motorSimulado {
            respond(
                content = cuerpo,
                status = status,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "X-Request-Id" to listOf("req-del-servidor"),
                ),
            )
        }
        return StockApi(
            config = testConfig(),
            tokens = tokenStoreWithSession(),
            engine = engine,
        ) to grabado
    }

    @Test
    fun `un 403 con cuerpo se traduce a PermissionDenied con el request id del servidor`() = runTest {
        val (api, _) = apiQueResponde(HttpStatusCode.Forbidden, body("PERMISSION_DENIED"))

        val resultado = api.products.list()

        val error = assertIs<Either.Left<ApiError>>(resultado).error
        assertIs<ApiError.PermissionDenied>(error)
        // El `X-Request-Id` es lo que se pega en un informe de incidencia.
        assertEquals("req-del-servidor", error.requestId)
    }

    @Test
    fun `un 422 con detalles se traduce a Validation y conserva available`() = runTest {
        val (api, _) = apiQueResponde(HttpStatusCode.UnprocessableEntity, body("INSUFFICIENT_STOCK"))

        val resultado = api.orders.export(1)

        val error = assertIs<Either.Left<ApiError>>(resultado).error
        val caso = assertIs<ApiError.InsufficientStock>(error)
        assertEquals(3.5, caso.available)
    }

    @Test
    fun `un 429 se marca reintentable para la cola de sincronizacion`() = runTest {
        val (api, _) = apiQueResponde(HttpStatusCode.TooManyRequests, body("RATE_LIMITED"))

        val error = assertIs<Either.Left<ApiError>>(api.products.list()).error

        assertIs<ApiError.RateLimited>(error)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `un 502 de un proxy con HTML no se confunde con una respuesta malformada`() = runTest {
        val (api, _) = apiQueResponde(HttpStatusCode.BadGateway, "<html>502 Bad Gateway</html>")

        val error = assertIs<Either.Left<ApiError>>(api.products.list()).error

        // El servidor está caído: eso es lo accionable, no que el JSON no cuadre.
        assertIs<ApiError.ServerUnavailable>(error)
    }

    @Test
    fun `un 204 se decodifica como exito sin intentar leer un cuerpo`() = runTest {
        val (engine, grabado) = motorSimulado {
            respond("", HttpStatusCode.NoContent)
        }
        val api = StockApi(config = testConfig(), tokens = tokenStoreWithSession(), engine = engine)

        val resultado = api.auth.logout()

        assertIs<Either.Right<Unit>>(resultado)
        assertEquals(1, grabado.vecesRuta("/v1/auth/logout"))
    }
}
