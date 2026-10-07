package dev.axzy.stock.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL CONTRATO COMÚN DE `KeyValueStore`
 *
 * Se escribe **una sola vez** como una función que recibe cualquier
 * implementación, y se ejecuta dos veces: con el doble en memoria (aquí) y con
 * el `actual` real de JVM (en `jvmTest`). Así el contrato se prueba de verdad y
 * no solo «en el caso que el doble implementa bien».
 *
 * Es la forma de que un `actual` de Android o iOS que se desvíe del contrato se
 * detecte: si el `actual` de JVM pasa el mismo contrato, el contrato está bien
 * especificado, y los otros dos se contrastan contra él cuando se ejecutan en su
 * plataforma.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object KeyValueStoreContract {

    suspend fun verificar(store: KeyValueStore) {
        // ── Vacío al principio ──────────────────────────────────────────────
        store.clear()
        assertEquals(0, store.keys().size, "un almacén recién limpiado no tiene claves")
        assertNull(store.getString("inexistente"))

        // ── Ida y vuelta ────────────────────────────────────────────────────
        store.putString("server_config", "http://10.0.2.2:8080/api/v1")
        assertEquals("http://10.0.2.2:8080/api/v1", store.getString("server_config"))
        assertEquals(setOf("server_config"), store.keys())

        // ── Reemplazo ───────────────────────────────────────────────────────
        store.putString("server_config", "https://almacen.interno/api/v1")
        assertEquals("https://almacen.interno/api/v1", store.getString("server_config"))
        assertEquals(1, store.keys().size, "reemplazar no debe crear una clave nueva")

        // ── Varias claves ───────────────────────────────────────────────────
        store.putString("language", "es")
        assertEquals(setOf("server_config", "language"), store.keys())

        // ── Borrado de una clave ────────────────────────────────────────────
        store.remove("language")
        assertNull(store.getString("language"))
        assertEquals(setOf("server_config"), store.keys())

        // ── `null` en `put` es un borrado ───────────────────────────────────
        store.putString("language", "es")
        store.putString("language", null)
        assertNull(store.getString("language"))

        // ── Limpieza total ──────────────────────────────────────────────────
        store.putString("otra", "x")
        store.clear()
        assertEquals(0, store.keys().size)
        assertNull(store.getString("server_config"))
    }
}

/** El contrato, ejecutado contra el doble: si el doble no lo cumple, ningún test vale. */
class KeyValueStoreTest {

    @Test
    fun `el doble en memoria cumple el contrato`() = runTest {
        KeyValueStoreContract.verificar(FakeKeyValueStore())
    }

    @Test
    fun `observar una clave emite el valor actual y los cambios`() = runTest {
        val store = FakeKeyValueStore()
        store.putString("language", "es")

        assertEquals("es", store.observeString("language").first())

        store.putString("language", "en")
        assertEquals("en", store.observeString("language").first())

        store.remove("language")
        assertNull(store.observeString("language").first())
    }

    @Test
    fun `clear de un almacen no afecta a otro`() = runTest {
        // Cada almacén es un espacio aislado: borrar las preferencias de la app
        // no puede llevarse por delante la configuración del servidor.
        val uno = FakeKeyValueStore()
        val otro = FakeKeyValueStore()

        uno.putString("a", "1")
        otro.putString("b", "2")
        uno.clear()

        assertEquals(0, uno.keys().size)
        assertEquals(setOf("b"), otro.keys())
        assertTrue(otro.getString("b") == "2")
    }
}

/**
 * El contrato de `SecureStore`.
 *
 * Se prueba aparte del de preferencias porque **tiene una obligación más**: lo
 * que guarda no puede aparecer en claro en un almacén legible. En el doble eso
 * se comprueba con [FakeSecureStore.valorCrudo] (lo que hay dentro es el valor,
 * pero el doble representa el almacén cifrado); en el `actual` de Android lo que
 * hay dentro de `SharedPreferences` es el sobre cifrado, y eso lo verifica el
 * test de Android cuando se ejecuta allí.
 */
object SecureStoreContract {

    suspend fun verificar(store: SecureStore) {
        store.clear()
        assertNull(store.get("access_token"))

        store.put("access_token", "eyJ...")
        store.put("refresh_token", "8Kd3...")
        assertEquals("eyJ...", store.get("access_token"))
        assertEquals("8Kd3...", store.get("refresh_token"))

        store.put("access_token", "eyJ-nuevo...")
        assertEquals("eyJ-nuevo...", store.get("access_token"))

        store.remove("access_token")
        assertNull(store.get("access_token"))
        // Borrar una clave no puede llevarse las demás.
        assertEquals("8Kd3...", store.get("refresh_token"))

        store.clear()
        assertNull(store.get("refresh_token"))
    }
}

class SecureStoreTest {

    @Test
    fun `el doble en memoria cumple el contrato`() = runTest {
        SecureStoreContract.verificar(FakeSecureStore())
    }
}
