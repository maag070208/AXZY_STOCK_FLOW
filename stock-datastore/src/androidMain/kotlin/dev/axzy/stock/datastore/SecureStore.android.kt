package dev.axzy.stock.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL ALMACÉN SEGURO DE ANDROID: AndroidKeyStore + AES/GCM
 *
 * El refresh token se cifra con una clave AES que **vive dentro del
 * AndroidKeyStore** y no se puede extraer del dispositivo: está en el TEE (o en
 * un elemento seguro, si el teléfono lo tiene). Aunque alguien copie el archivo
 * de preferencias, sin ese teléfono concreto no puede descifrar nada.
 *
 * **Por qué no `androidx.security:security-crypto`:** su
 * `EncryptedSharedPreferences` está en desuso, arrastra una dependencia que a su
 * vez depende de versiones concretas de Tink, y lo que hace por dentro es
 * exactamente esto. Hacerlo aquí son sesenta líneas **verificables** en lugar de
 * una caja negra con su propio ciclo de vida de versiones.
 *
 * El texto cifrado (IV + ciphertext) sí se guarda en `SharedPreferences`: es
 * ilegible sin la clave, y así el almacén seguro no necesita su propio archivo.
 * Aquí `SharedPreferences` es aceptable porque **lo que se guarda no es el
 * secreto**: es su sobre cifrado.
 * ═══════════════════════════════════════════════════════════════════════════
 */
internal class AndroidKeystoreSecureStore(
    private val nombre: String,
) : SecureStore {

    private val preferencias = AndroidDatastoreContext.requireContext()
        .getSharedPreferences("stock_seguro_$nombre", Context.MODE_PRIVATE)

    private val alias: String = "stock.seguro.$nombre"

    override suspend fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMACION)
        cipher.init(Cipher.ENCRYPT_MODE, clave())
        val cifrado = cipher.doFinal(value.encodeToByteArray())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val datos = Base64.encodeToString(cifrado, Base64.NO_WRAP)
        // `commit()` y no `apply()`: si el token no se ha escrito en disco, la
        // app NO debe creer que la sesión está guardada. `apply()` devolvería el
        // control antes de saberlo.
        preferencias.edit().putString(key, "$iv:$datos").commit()
    }

    override suspend fun get(key: String): String? {
        val guardado = preferencias.getString(key, null) ?: return null
        val partes = guardado.split(':')
        if (partes.size != 2) {
            // Un valor con forma inesperada no es un secreto: se descarta.
            remove(key)
            return null
        }

        return try {
            val iv = Base64.decode(partes[0], Base64.NO_WRAP)
            val cifrado = Base64.decode(partes[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMACION)
            cipher.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(cifrado).decodeToString()
        } catch (t: Throwable) {
            // Clave rotada, copia de seguridad restaurada en otro dispositivo o
            // dato corrupto. Se trata como «no hay sesión»: es la respuesta
            // segura, y evita que la app se caiga al arrancar por un token viejo.
            remove(key)
            null
        }
    }

    override suspend fun remove(key: String) {
        preferencias.edit().remove(key).commit()
    }

    override suspend fun clear() {
        preferencias.edit().clear().commit()
    }

    /**
     * La clave AES, creada la primera vez y **residente en el KeyStore**.
     *
     * `setUserAuthenticationRequired(false)` a propósito: exigir huella en cada
     * lectura dejaría la sincronización en segundo plano sin poder leer el token.
     * Lo que protege aquí es que la clave no se pueda extraer, no que haga falta
     * el dedo para usarla.
     */
    private fun clave(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generador.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generador.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMACION = "AES/GCM/NoPadding"

        /** 256 bits: el máximo que admite AES. */
        const val KEY_SIZE_BITS = 256

        /** Longitud del tag de autenticación GCM, en bits. `128` es lo recomendado. */
        const val TAG_BITS = 128
    }
}

actual fun createSecureStore(name: String): SecureStore = AndroidKeystoreSecureStore(name)
