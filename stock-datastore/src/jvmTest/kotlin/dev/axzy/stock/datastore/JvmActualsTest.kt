package dev.axzy.stock.datastore

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LOS `actual` DE JVM, VERIFICADOS DE VERDAD
 *
 * El contrato común se prueba con dobles en `commonTest`; aquí se ejecuta el
 * **mismo contrato** contra la implementación real de JVM. Sin esto, el doble
 * pasaría los tests y el `actual` podría estar haciendo cualquier cosa: es el
 * falso verde clásico de «lo probé con un mock».
 *
 * El directorio se apunta a `build/tmp` con la propiedad de sistema
 * `stock.datastore.dir` para no escribir en el directorio del usuario.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class JvmActualsTest {

    @Test
    fun `el almacen de preferencias de JVM cumple el contrato comun`() = runTest {
        System.setProperty("stock.datastore.dir", "build/tmp/datastore-pruebas")
        val store = createKeyValueStore("contrato-jvm")

        KeyValueStoreContract.verificar(store)
    }

    @Test
    fun `el almacen de preferencias de JVM persiste en disco`() = runTest {
        System.setProperty("stock.datastore.dir", "build/tmp/datastore-pruebas")
        val primera = createKeyValueStore("persistencia-jvm")
        primera.clear()
        primera.putString("server_config", "https://almacen.interno/api/v1")

        // Otra instancia del mismo almacén: es lo que pasa al reiniciar la app.
        val segunda = createKeyValueStore("persistencia-jvm")
        assertEquals("https://almacen.interno/api/v1", segunda.getString("server_config"))

        segunda.clear()
        assertEquals(0, createKeyValueStore("persistencia-jvm").keys().size)
    }

    @Test
    fun `dos almacenes distintos de JVM no se pisan`() = runTest {
        System.setProperty("stock.datastore.dir", "build/tmp/datastore-pruebas")
        val uno = createKeyValueStore("almacen-uno")
        val otro = createKeyValueStore("almacen-dos")
        uno.clear()
        otro.clear()

        uno.putString("clave", "uno")
        otro.putString("clave", "dos")

        assertEquals("uno", uno.getString("clave"))
        assertEquals("dos", otro.getString("clave"))

        uno.clear()
        assertEquals("dos", otro.getString("clave"))
    }

    @Test
    fun `el almacen seguro de JVM cumple el contrato comun`() = runTest {
        SecureStoreContract.verificar(createSecureStore("contrato-seguro-jvm"))
    }

    @Test
    fun `el almacen seguro de JVM NO persiste, a proposito`() = runTest {
        val primera = createSecureStore("sin-persistencia")
        primera.put("refresh_token", "8Kd3...")

        // No hay un almacén seguro en la JVM, y escribir un refresh token en
        // claro en disco sería peor que no persistirlo: nadie se daría cuenta de
        // que está ahí.
        val segunda = createSecureStore("sin-persistencia")
        assertNull(segunda.get("refresh_token"))
        assertNotNull(primera.get("refresh_token"))
    }

    @Test
    fun `la sesion sobrevive en el almacen de JVM solo mientras vive el proceso`() = runTest {
        val store = SessionStore(
            createSecureStore("sesion-jvm"),
            nowEpochSeconds = { 1_700_000_000L },
        )
        store.clear()
        store.save(StoredTokens("access", "refresh", 1_700_000_900L))

        assertEquals("access", store.validAccessToken())
        assertTrue(store.load() != null)
        store.clear()
        assertNull(store.load())
    }
}
