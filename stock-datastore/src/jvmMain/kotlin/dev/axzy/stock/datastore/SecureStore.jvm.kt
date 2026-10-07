package dev.axzy.stock.datastore

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL ALMACÉN SEGURO DE LA JVM: EN MEMORIA, A PROPÓSITO
 *
 * **No hay un almacén seguro en la JVM.** No existe un Keystore del sistema al
 * que pedir una clave que no se pueda extraer, y el `KeyStore` de Java es un
 * archivo que se abre con una contraseña que tendría que estar... en el código.
 * Eso no es cifrado, es ofuscación.
 *
 * Ante esa disyuntiva, la decisión es explícita: **se guarda en memoria**. Un
 * refresh token de 30 días escrito en claro en el disco de la máquina de
 * desarrollo —o en un servidor compartido— es una sesión regalada, y es peor que
 * no persistirla, porque nadie se da cuenta de que está ahí.
 *
 * Consecuencia honesta: en la JVM, cerrar el proceso cierra la sesión. Quien
 * necesite persistencia en JVM debe inyectar su propia implementación de
 * [SecureStore]; el móvil no pasa por aquí (usa Keystore y Keychain).
 * ═══════════════════════════════════════════════════════════════════════════
 */
internal class JvmInMemorySecureStore : SecureStore {

    private val valores = mutableMapOf<String, String>()

    override suspend fun put(key: String, value: String) {
        valores[key] = value
    }

    override suspend fun get(key: String): String? = valores[key]

    override suspend fun remove(key: String) {
        valores.remove(key)
    }

    override suspend fun clear() {
        valores.clear()
    }
}

actual fun createSecureStore(name: String): SecureStore = JvmInMemorySecureStore()
