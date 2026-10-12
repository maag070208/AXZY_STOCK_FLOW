@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.axzy.stock.mobile.core.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

actual fun secureStore(): KeyValueStore = KeychainStore

actual fun settingsStore(): KeyValueStore = DefaultsStore

private object DefaultsStore : KeyValueStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults
    override fun get(key: String): String? = defaults.stringForKey(key)
    override fun put(key: String, value: String) = defaults.setObject(value, forKey = key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)
}

/**
 * Values are generic passwords of the iOS Keychain, readable after the first unlock and never copied to another
 * device or to a backup. The Keychain survives uninstalling the app, so a token stored by an earlier install may
 * still be there: it is only a refresh token, and the API refuses it once it has expired or been rotated.
 */
private object KeychainStore : KeyValueStore {

    private const val SERVICE = "dev.axzy.stock.mobile.session"

    override fun get(key: String): String? = memScoped {
        val query = query(key, kSecReturnData to cf(kCFBooleanTrue), kSecMatchLimit to cf(kSecMatchLimitOne))
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFBridgingRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun put(key: String, value: String) {
        remove(key)
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = query(
            key,
            kSecValueData to data,
            kSecAttrAccessible to cf(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
        )
        SecItemAdd(query, null)
        CFBridgingRelease(query)
    }

    override fun remove(key: String) {
        val query = query(key)
        SecItemDelete(query)
        CFBridgingRelease(query)
    }

    /** A retained Keychain query for [key]; whoever calls this releases it with `CFBridgingRelease`. */
    private fun query(key: String, vararg extra: Pair<CFTypeRef?, Any?>): CFDictionaryRef {
        val attributes = mutableMapOf<Any?, Any?>(
            cf(kSecClass) to cf(kSecClassGenericPassword),
            cf(kSecAttrService) to SERVICE,
            cf(kSecAttrAccount) to key,
        )
        extra.forEach { (name, value) -> attributes[cf(name)] = value }
        return checkNotNull(CFBridgingRetain(attributes)).reinterpret()
    }

    /** A Core Foundation constant as an object Kotlin can put in a map. The constant is retained first, so it is never over-released. */
    private fun cf(ref: CFTypeRef?): Any? = CFBridgingRelease(CFRetain(ref))
}
