package dev.axzy.stock.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Dobles en memoria para probar el **contrato común**.
 *
 * Los `actual` de plataforma se verifican aparte (en `jvmTest`, contra el
 * almacén real de JVM), porque probar el contrato con un doble y creer que eso
 * valida DataStore o el Keychain sería exactamente el falso verde que este
 * proyecto quiere evitar.
 */
class FakeKeyValueStore : KeyValueStore {

    private val valores = mutableMapOf<String, String>()
    private val cambios = MutableStateFlow(0L)

    /** Cuántas veces se ha llamado a `clear()`. Lo usan los tests del contrato. */
    var limpiezas: Int = 0
        private set

    override suspend fun getString(key: String): String? = valores[key]

    override suspend fun putString(key: String, value: String?) {
        if (value == null) valores.remove(key) else valores[key] = value
        cambios.value += 1
    }

    override suspend fun remove(key: String) {
        valores.remove(key)
        cambios.value += 1
    }

    override suspend fun clear() {
        valores.clear()
        limpiezas += 1
        cambios.value += 1
    }

    override suspend fun keys(): Set<String> = valores.keys.toSet()

    override fun observeString(key: String): Flow<String?> = cambios.map { valores[key] }
}

/** Almacén seguro en memoria, para el contrato de `SecureStore`. */
class FakeSecureStore(private val fallaElDescifrado: Boolean = false) : SecureStore {

    private val valores = mutableMapOf<String, String>()

    override suspend fun put(key: String, value: String) {
        valores[key] = value
    }

    override suspend fun get(key: String): String? {
        // Simula el caso real de una clave rotada o una copia restaurada en otro
        // dispositivo: hay un valor, pero no se puede leer.
        if (fallaElDescifrado) return null
        return valores[key]
    }

    override suspend fun remove(key: String) {
        valores.remove(key)
    }

    override suspend fun clear() {
        valores.clear()
    }

    /** Solo para las aserciones de los tests: comprueba que NO se guarda en claro. */
    fun valorCrudo(key: String): String? = valores[key]
}
