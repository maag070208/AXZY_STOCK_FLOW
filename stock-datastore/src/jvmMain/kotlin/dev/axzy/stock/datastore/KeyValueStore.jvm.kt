package dev.axzy.stock.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Properties

/**
 * El almacén de preferencias de la JVM: un archivo `Properties`.
 *
 * **Es una implementación de conveniencia para tests y para el backend, no el
 * almacén del móvil.** En Android manda DataStore y en iOS `NSUserDefaults`;
 * aquí lo que hace falta es poder ejecutar el contrato común en milisegundos,
 * sin emulador.
 *
 * El directorio se toma de la propiedad de sistema `stock.datastore.dir`, para
 * que un test pueda apuntarlo a `build/tmp` en lugar de escribir en el directorio
 * del usuario.
 */
internal class JvmFileKeyValueStore(private val archivo: File) : KeyValueStore {

    private val mutex = Mutex()
    private val properties = Properties()

    /** Cambia en cada escritura: es lo que permite observar sin sondear. */
    private val cambios = MutableStateFlow(0L)

    init {
        archivo.parentFile?.mkdirs()
        if (archivo.exists()) {
            runCatching { archivo.inputStream().use { properties.load(it) } }
        }
    }

    override suspend fun getString(key: String): String? = mutex.withLock { properties.getProperty(key) }

    override suspend fun putString(key: String, value: String?) = mutex.withLock {
        if (value == null) properties.remove(key) else properties.setProperty(key, value)
        guardar()
    }

    override suspend fun remove(key: String) = mutex.withLock {
        properties.remove(key)
        guardar()
    }

    override suspend fun clear() = mutex.withLock {
        properties.clear()
        guardar()
    }

    override suspend fun keys(): Set<String> = mutex.withLock {
        properties.stringPropertyNames().toSet()
    }

    override fun observeString(key: String): Flow<String?> =
        cambios.map { properties.getProperty(key) }

    private fun guardar() {
        archivo.outputStream().use { properties.store(it, "Preferencias de Almacén Integral") }
        cambios.value += 1
    }
}

/**
 * Crea el almacén de preferencias de JVM.
 *
 * @param name nombre lógico del almacén; se convierte en `$name.properties`
 *   dentro del directorio configurado.
 */
actual fun createKeyValueStore(name: String): KeyValueStore =
    JvmFileKeyValueStore(File(directorioBase(), "$name.properties"))

private fun directorioBase(): File {
    val configurado = System.getProperty("stock.datastore.dir")
    val base = if (configurado.isNullOrBlank()) {
        File(System.getProperty("user.home"), ".stock-datastore")
    } else {
        File(configurado)
    }
    return File(base, "almacenes").apply { mkdirs() }
}
