package dev.axzy.stock.mobile.core.storage

/** A [KeyValueStore] that lives and dies with the process: what tests use, and nothing else. */
class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
