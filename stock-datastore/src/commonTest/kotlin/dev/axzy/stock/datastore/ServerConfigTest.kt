package dev.axzy.stock.datastore

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA CONFIGURACIÓN DEL SERVIDOR
 *
 * El legacy guardaba una IP pelada, así que el cliente tenía que adivinar
 * esquema, puerto y prefijo. Cada adivinanza era un sitio donde cliente y
 * servidor podían no coincidir, y el síntoma era «no conecta» sin más.
 *
 * Los casos que se prueban son los que se teclean de verdad en una bodega: la
 * URL completa del servidor corporativo y el `host:puerto` de la red interna.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ServerConfigTest {

    @Test
    fun `acepta una URL completa con esquema, puerto y ruta`() {
        val config = assertNotNull(
            ServerConfig.parse("https://almacen.interno:8443/api/v1").getOrNull()
        )

        assertEquals(ServerConfig.Scheme.HTTPS, config.scheme)
        assertEquals("almacen.interno", config.host)
        assertEquals(8443, config.port)
        assertEquals("/api/v1", config.basePath)
        assertEquals("https://almacen.interno:8443/api/v1", config.toBaseUrl())
    }

    @Test
    fun `acepta el atajo de la red interna sin esquema`() {
        // Es lo que se teclea en el piso: la IP y el puerto.
        val config = assertNotNull(ServerConfig.parse("10.0.2.2:8080").getOrNull())

        assertEquals(ServerConfig.Scheme.HTTP, config.scheme)
        assertEquals("10.0.2.2", config.host)
        assertEquals(8080, config.port)
        // El prefijo de la API por defecto: el contrato lo exige y el operador no
        // tiene por qué sabérselo.
        assertEquals("/api/v1", config.basePath)
        assertEquals("http://10.0.2.2:8080/api/v1", config.toBaseUrl())
    }

    @Test
    fun `sin puerto se usan los del esquema`() {
        assertEquals(443, assertNotNull(ServerConfig.parse("https://almacen.interno").getOrNull()).port)
        assertEquals(80, assertNotNull(ServerConfig.parse("almacen.interno").getOrNull()).port)
    }

    @Test
    fun `el puerto por defecto no ensucia la URL, pero si se muestra`() {
        val config = assertNotNull(ServerConfig.parse("https://almacen.interno/api/v1").getOrNull())

        // `:443` funciona, pero es ruido en el registro y en la pantalla.
        assertEquals("https://almacen.interno/api/v1", config.toBaseUrl())
        // En la pantalla de configuración sí se muestra entero: el usuario tiene
        // que ver exactamente qué quedó guardado.
        assertEquals("https://almacen.interno:443/api/v1", config.toDisplayString())
    }

    @Test
    fun `rechaza una direccion vacia`() {
        assertTrue(ServerConfig.parse("   ").isFailure)
        assertTrue(ServerConfig.parse("").isFailure)
    }

    @Test
    fun `rechaza un puerto que no es un numero o esta fuera de rango`() {
        assertTrue(ServerConfig.parse("almacen.interno:abc").isFailure)
        assertTrue(ServerConfig.parse("almacen.interno:0").isFailure)
        assertTrue(ServerConfig.parse("almacen.interno:70000").isFailure)
        assertTrue(ServerConfig.parse("almacen.interno:").isFailure)
    }

    @Test
    fun `no se puede construir una configuracion incoherente`() {
        // El host no lleva esquema: mezclarlo es cómo el legacy acababa con
        // «http://http://…».
        assertTrue(
            runCatching {
                ServerConfig(ServerConfig.Scheme.HTTP, "http://almacen.interno", 80)
            }.isFailure
        )
        assertTrue(
            runCatching {
                ServerConfig(ServerConfig.Scheme.HTTP, "almacen.interno/api", 80)
            }.isFailure
        )
        assertTrue(
            runCatching {
                ServerConfig(ServerConfig.Scheme.HTTP, "", 80)
            }.isFailure
        )
        assertTrue(
            runCatching {
                ServerConfig(ServerConfig.Scheme.HTTP, "almacen.interno", 80, "api/v1")
            }.isFailure
        )
    }

    @Test
    fun `tolera barras finales de mas`() {
        val config = assertNotNull(ServerConfig.parse("http://10.0.2.2:8080/api/v1/").getOrNull())
        assertEquals("/api/v1", config.basePath)
        assertEquals("http://10.0.2.2:8080/api/v1", config.toBaseUrl())
    }
}

/** Persistencia de la configuración y de las preferencias de usuario. */
class PreferencesTest {

    @Test
    fun `la configuracion del servidor va y vuelve`() = runTest {
        val almacen = FakeKeyValueStore()
        val store = ServerConfigStore(almacen)

        assertNull(store.load())

        val config = ServerConfig(ServerConfig.Scheme.HTTPS, "almacen.interno", 8443)
        store.save(config)

        val leida = assertNotNull(store.load())
        assertEquals(config, leida)
        // Se guarda como UN valor: una escritura a medias no puede dejar un host
        // con el puerto de la configuración anterior.
        assertEquals(setOf("server_config"), almacen.keys())

        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `una configuracion guardada corrupta se trata como ausente`() = runTest {
        val almacen = FakeKeyValueStore()
        // Sin host: no hay nada que interpretar.
        almacen.putString("server_config", "http://")

        assertNull(ServerConfigStore(almacen).load())
    }

    @Test
    fun `un nombre de host pelado se acepta, porque es lo que hay en la red interna`() {
        // Deliberadamente permisivo: `almacen.interno` es un host válido en la
        // red de la bodega, y rechazarlo obligaría al operador a teclear el
        // esquema. Lo que NO se acepta es un valor incoherente (puerto no
        // numérico, host vacío, host con esquema dentro).
        val config = assertNotNull(ServerConfig.parse("almacen.interno").getOrNull())
        assertEquals("almacen.interno", config.host)
        assertEquals(80, config.port)
    }

    @Test
    fun `el idioma por defecto es el espanol y se puede cambiar`() = runTest {
        val prefs = UserPreferences(FakeKeyValueStore())

        assertEquals(AppLanguage.SPANISH, prefs.language())

        prefs.setLanguage(AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, prefs.language())

        // Un código desconocido no rompe: cae al idioma de la operación.
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromCode("fr"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromCode(null))
    }

    @Test
    fun `recuerda el ultimo usuario pero nunca la contrasena`() = runTest {
        val almacen = FakeKeyValueStore()
        val prefs = UserPreferences(almacen)

        assertNull(prefs.lastUsername())
        assertTrue(!prefs.biometricEnabled())

        prefs.setLastUsername("  jperez  ")
        assertEquals("jperez", prefs.lastUsername())

        prefs.setBiometricEnabled(true)
        assertTrue(prefs.biometricEnabled())

        // En un iPad compartido, el nombre ahorra teclearlo en cada cambio de
        // turno; la contraseña no se recuerda en ninguna parte.
        assertTrue(almacen.keys().none { it.contains("password", ignoreCase = true) })

        prefs.setLastUsername(null)
        assertNull(prefs.lastUsername())
    }

    @Test
    fun `borrar las preferencias de usuario no toca la configuracion del servidor`() = runTest {
        val almacen = FakeKeyValueStore()
        val prefs = UserPreferences(almacen)
        val servidor = ServerConfigStore(almacen)

        servidor.save(ServerConfig(ServerConfig.Scheme.HTTP, "10.0.2.2", 8080))
        prefs.setLastUsername("jperez")
        prefs.setLanguage(AppLanguage.ENGLISH)

        prefs.clear()

        assertNull(prefs.lastUsername())
        assertEquals(AppLanguage.SPANISH, prefs.language())
        // Cerrar sesión no puede obligar a volver a teclear la dirección del
        // servidor.
        assertNotNull(servidor.load())
    }
}
