package dev.axzy.stock.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSUserDefaults

/**
 * El almacén de preferencias de iOS: **NSUserDefaults**.
 *
 * Cada nombre de almacén usa su propio *suite*, no el estándar: así `clear()` de
 * un almacén no borra las preferencias de la app ni las de otro almacén. Sin
 * suite, la única forma de borrar sería enumerar claves y adivinar cuáles son
 * nuestras, que es como se borra de más.
 *
 * Se añade un prefijo a cada clave por el mismo motivo: las suites de
 * `NSUserDefaults` las comparte el sistema con otros usos, y un prefijo deja
 * claro qué es nuestro.
 */
internal class UserDefaultsKeyValueStore(
    private val nombre: String,
) : KeyValueStore {

    private val defaults: NSUserDefaults =
        NSUserDefaults(suiteName = nombre) ?: NSUserDefaults.standardUserDefaults

    /** Cambia en cada escritura: permite observar sin sondear. */
    private val cambios = MutableStateFlow(0L)

    private fun clave(key: String) = "$nombre.$key"

    override suspend fun getString(key: String): String? = defaults.stringForKey(clave(key))

    override suspend fun putString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(clave(key))
        } else {
            defaults.setObject(value, clave(key))
        }
        cambios.value += 1
    }

    override suspend fun remove(key: String) {
        defaults.removeObjectForKey(clave(key))
        cambios.value += 1
    }

    override suspend fun clear() {
        // Solo las claves de ESTE almacén: el prefijo es lo que lo hace posible.
        clavesConPrefijo().forEach { defaults.removeObjectForKey(it) }
        cambios.value += 1
    }

    override suspend fun keys(): Set<String> =
        clavesConPrefijo().map { it.removePrefix("$nombre.") }.toSet()

    override fun observeString(key: String): Flow<String?> =
        cambios.map { defaults.stringForKey(clave(key)) }

    private fun clavesConPrefijo(): List<String> =
        defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith("$nombre.") }
}

actual fun createKeyValueStore(name: String): KeyValueStore = UserDefaultsKeyValueStore(name)
