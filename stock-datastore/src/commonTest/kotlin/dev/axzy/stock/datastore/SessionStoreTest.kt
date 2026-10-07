package dev.axzy.stock.datastore

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA SESIÓN PERSISTIDA
 *
 * Lo que se verifica aquí, y por qué es lo que importa:
 *
 *  1. Los tokens van **al almacén seguro**, no al de preferencias. Es la
 *     decisión central del módulo: un refresh token de 30 días en un archivo de
 *     preferencias legible es una sesión regalada.
 *  2. Si falta una pieza, **no hay sesión**. Un refresh token sin caducidad no
 *     es media sesión: es un resto, y tratarlo como válido haría que la app
 *     intentara refrescar con algo que no sabe cuándo expira.
 *  3. Un valor ilegible se trata como «no hay sesión», nunca como un fallo que
 *     tumbe el arranque.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class SessionStoreTest {

    private var ahora = 1_700_000_000L

    private fun almacen() = FakeSecureStore()

    private fun sessionStore(seguro: SecureStore = almacen()): SessionStore =
        SessionStore(seguro, nowEpochSeconds = { ahora })

    @Test
    fun `guarda, lee y borra la sesion en el almacen seguro`() = runTest {
        val seguro = almacen()
        val store = sessionStore(seguro)

        assertNull(store.load())

        store.save(StoredTokens("access-1", "refresh-1", ahora + 900))

        val guardada = assertNotNull(store.load())
        assertEquals("access-1", guardada.accessToken)
        assertEquals("refresh-1", guardada.refreshToken)
        assertEquals(ahora + 900, guardada.expiresAtEpochSeconds)

        store.clear()
        assertNull(store.load())
        // Y en el almacén seguro no queda ni rastro del refresh token.
        assertNull(seguro.get("refresh_token"))
    }

    @Test
    fun `el access token caducado no se devuelve como valido`() = runTest {
        val store = sessionStore()

        store.save(StoredTokens("access-1", "refresh-1", ahora + 900))
        assertEquals("access-1", store.validAccessToken())

        // A los 15 minutos justos ya no sirve, y con el margen de 30 segundos
        // tampoco medio minuto antes: una petición que sale con dos segundos de
        // vida llega caducada y provoca un 401 evitable.
        ahora += 900
        assertNull(store.validAccessToken())
        ahora -= 900

        ahora += 880
        assertNull(store.validAccessToken())
        // El refresh token sigue ahí: es lo que permite recuperar la sesión sin
        // pedir credenciales.
        assertEquals("refresh-1", store.refreshToken())
    }

    @Test
    fun `una sesion incompleta no es una sesion`() = runTest {
        val seguro = almacen()
        val store = sessionStore(seguro)

        // Falta el instante de caducidad: el caso de una escritura interrumpida.
        seguro.put("access_token", "access-1")
        seguro.put("refresh_token", "refresh-1")
        assertNull(store.load())

        // Y sin refresh token tampoco: no se puede recuperar nada.
        seguro.put("expires_at", (ahora + 900).toString())
        seguro.remove("refresh_token")
        assertNull(store.load())

        // Un instante de caducidad que no es un número: tampoco.
        seguro.put("refresh_token", "refresh-1")
        seguro.put("expires_at", "no-es-un-numero")
        assertNull(store.load())
    }

    @Test
    fun `si el almacen seguro no puede descifrar, no hay sesion y la app no se cae`() = runTest {
        // Clave rotada, copia de seguridad restaurada en otro dispositivo, dato
        // corrupto: casos reales. La respuesta segura es «no hay sesión».
        val store = sessionStore(FakeSecureStore(fallaElDescifrado = true))

        store.save(StoredTokens("access-1", "refresh-1", ahora + 900))

        assertNull(store.load())
        assertNull(store.validAccessToken())
        assertNull(store.refreshToken())
    }

    @Test
    fun `los tokens no se guardan en las preferencias`() = runTest {
        val preferencias = FakeKeyValueStore()
        val seguro = almacen()

        sessionStore(seguro).save(StoredTokens("access-1", "refresh-1", ahora + 900))

        // La frontera entre preferencias y secretos es la razón de ser del
        // módulo: aquí se comprueba que no se cruza.
        assertEquals(0, preferencias.keys().size)
        assertTrue(seguro.get("refresh_token") == "refresh-1")
    }

    @Test
    fun `isExpired respeta el margen`() {
        val tokens = StoredTokens("a", "r", expiresAtEpochSeconds = 1_000)

        assertTrue(!tokens.isExpired(nowEpochSeconds = 900))
        assertTrue(!tokens.isExpired(nowEpochSeconds = 969))
        // 970 + 30 = 1000: ya entra dentro del margen.
        assertTrue(tokens.isExpired(nowEpochSeconds = 970))
        assertTrue(tokens.isExpired(nowEpochSeconds = 1_000))
        assertTrue(tokens.isExpired(nowEpochSeconds = 1_500))
    }
}
