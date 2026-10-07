package dev.axzy.stock.datastore

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.getBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL ALMACÉN SEGURO DE iOS: EL KEYCHAIN
 *
 * `kSecClassGenericPassword` con `kSecAttrAccessibleAfterFirstUnlock`:
 *
 *  · **Cifrado por el sistema** con la clave del dispositivo. El archivo del
 *    Keychain es ilegible aunque alguien se lleve una copia de la app.
 *  · **`AfterFirstUnlock`** y no `WhenUnlocked`: el operador enciende el iPad a
 *    primera hora, lo desbloquea una vez, y a partir de ahí la sincronización en
 *    segundo plano puede leer el token. Con `WhenUnlocked`, la app dejaría de
 *    sincronizar en cuanto se bloqueara la pantalla, que es la mitad del turno.
 *  · **No `ThisDeviceOnly`**: se permite la migración mediante copia de
 *    seguridad cifrada, que es lo que espera quien cambia de iPad.
 *
 * Los atributos van en un `CFDictionary` porque `SecItem*` es API de
 * CoreFoundation, no de Foundation.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class KeychainSecureStore(
    private val servicio: String,
) : SecureStore {

    override suspend fun put(key: String, value: String) {
        // El Keychain no tiene «upsert»: o se añade o se actualiza. Se borra y se
        // añade, lo que además cubre el caso de un valor anterior ilegible.
        remove(key)

        val consulta = diccionarioBase(key)
        val datos = value.aNSData()
        agregar(consulta, kSecValueData, CFBridgingRetain(datos))
        agregar(consulta, kSecAttrAccessible, CFBridgingRetain(kSecAttrAccessibleAfterFirstUnlock))
        SecItemAdd(consulta, null)
        CFRelease(consulta)
    }

    override suspend fun get(key: String): String? = memScoped {
        val consulta = diccionarioBase(key)
        agregar(consulta, kSecReturnData, CFBridgingRetain(kCFBooleanTrue))
        agregar(consulta, kSecMatchLimit, CFBridgingRetain(kSecMatchLimitOne))

        val resultado = alloc<CFTypeRefVar>()
        val estado = SecItemCopyMatching(consulta, resultado.ptr)
        CFRelease(consulta)

        if (estado != errSecSuccess) return@memScoped null

        val datos = CFBridgingRelease(resultado.value) as? NSData ?: return@memScoped null
        datos.aTexto()
    }

    override suspend fun remove(key: String) {
        val consulta = diccionarioBase(key)
        SecItemDelete(consulta)
        CFRelease(consulta)
    }

    override suspend fun clear() {
        // Se borra **por servicio**: todas las cuentas de este almacén. Sin
        // `kSecAttrAccount`, `SecItemDelete` borra todo lo que coincida con el
        // resto de la consulta, que aquí es exactamente «lo de este servicio».
        val consulta = diccionarioVacio()
        agregar(consulta, kSecClass, CFBridgingRetain(kSecClassGenericPassword))
        agregar(consulta, kSecAttrService, CFBridgingRetain(servicio as NSString))
        SecItemDelete(consulta)
        CFRelease(consulta)
    }

    /** La consulta que identifica **un** secreto: clase, servicio y cuenta. */
    private fun diccionarioBase(key: String): CFMutableDictionaryRef {
        val consulta = diccionarioVacio()
        agregar(consulta, kSecClass, CFBridgingRetain(kSecClassGenericPassword))
        agregar(consulta, kSecAttrService, CFBridgingRetain(servicio as NSString))
        agregar(consulta, kSecAttrAccount, CFBridgingRetain(key as NSString))
        return consulta
    }

    private fun diccionarioVacio(): CFMutableDictionaryRef =
        CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!

    /**
     * Añade un par al diccionario y libera la referencia que se creó al puentear.
     *
     * El diccionario **retiene** el valor al añadirlo (los callbacks son de tipo
     * `kCFType…`), así que dejar la referencia con +1 sería una fuga: pequeña,
     * pero una por cada guardado de sesión.
     */
    private fun agregar(diccionario: CFMutableDictionaryRef, clave: CFTypeRef?, valor: CFTypeRef?) {
        CFDictionaryAddValue(diccionario, clave, valor)
        valor?.let { CFRelease(it) }
    }
}

/** Un `String` de Kotlin como `NSData` en UTF-8. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.aNSData(): NSData {
    val bytes = encodeToByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
}

/** Los bytes de un `NSData` como texto UTF-8. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.aTexto(): String? {
    if (length == 0UL) return ""
    val bytes = ByteArray(length.toInt())
    bytes.usePinned { pinned ->
        getBytes(pinned.addressOf(0), length)
    }
    return bytes.decodeToString()
}

actual fun createSecureStore(name: String): SecureStore = KeychainSecureStore(name)
