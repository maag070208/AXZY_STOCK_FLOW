package dev.axzy.stock.mobile.core.storage

/** A small store of text values by key, kept on the device between launches. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/**
 * Where a secret goes: the iOS Keychain, or a key of the Android Keystore that encrypts what is written. A value
 * that cannot be read back (the key was lost, the data was restored on another device) reads as absent.
 */
expect fun secureStore(): KeyValueStore

/** Where a setting that is not a secret goes: `NSUserDefaults` on iOS, `SharedPreferences` on Android. */
expect fun settingsStore(): KeyValueStore
