package dev.axzy.stock.mobile.core.network

import dev.axzy.stock.mobile.core.storage.KeyValueStore

/**
 * Where the app finds the API. The operator can change it on the sign-in screen, so the same build works against the
 * local stack, a staging server or production; until they do, it is the platform's local address
 * ([defaultApiBaseUrl]). Only [normalize]d addresses are ever kept.
 */
class ApiAddress(private val store: KeyValueStore, private val default: String = defaultApiBaseUrl) {

    /** The address in use, without a trailing slash. */
    val url: String get() = store.get(KEY) ?: default

    /** Keeps [raw] as the address if it is a valid one. False, and nothing changes, if it is not. */
    fun save(raw: String): Boolean {
        val address = normalize(raw) ?: return false
        if (address == default) store.remove(KEY) else store.put(KEY, address)
        return true
    }

    companion object {
        private const val KEY = "api_base_url"

        /**
         * The address as the client uses it, or null if it cannot be one: it must start with `http://` or `https://`,
         * name a host, and carry no spaces, query or fragment. A path is allowed (an API behind a proxy prefix); a
         * trailing slash is dropped, because every route the client appends starts with one.
         */
        fun normalize(raw: String): String? {
            val address = raw.trim().trimEnd('/')
            val host = when {
                address.startsWith("http://", ignoreCase = true) -> address.substring("http://".length)
                address.startsWith("https://", ignoreCase = true) -> address.substring("https://".length)
                else -> return null
            }
            if (host.isEmpty() || host.startsWith('/') || host.any { it.isWhitespace() || it == '?' || it == '#' }) return null
            return address
        }
    }
}
