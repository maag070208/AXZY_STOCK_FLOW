package dev.axzy.stock.mobile.core.storage

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The real iOS Keychain and `NSUserDefaults`, run on the simulator (`./gradlew :shared:iosSimulatorArm64Test
 * -PenableIosTargets=true`). The Keychain cannot be faked: what matters is that the platform accepts what is written.
 */
class StorageIosTest {

    private val keys = listOf("test_key_a", "test_key_b")

    @AfterTest
    fun clean() {
        keys.forEach {
            secureStore().remove(it)
            settingsStore().remove(it)
        }
    }

    @Test
    fun `a secret written to the keychain is read back replaced and removed`() {
        val store = secureStore()
        assertNull(store.get("test_key_a"))

        store.put("test_key_a", "refresh-token-1")
        assertEquals("refresh-token-1", store.get("test_key_a"))

        store.put("test_key_a", "refresh-token-2")
        assertEquals("refresh-token-2", store.get("test_key_a"))

        store.remove("test_key_a")
        assertNull(store.get("test_key_a"))
    }

    @Test
    fun `keys do not see each other and removing a key that is not there is harmless`() {
        val store = secureStore()
        store.put("test_key_a", "one")
        store.put("test_key_b", "two")

        store.remove("test_key_a")
        store.remove("test_key_a")

        assertNull(store.get("test_key_a"))
        assertEquals("two", store.get("test_key_b"))
    }

    @Test
    fun `text with accents and symbols survives the keychain`() {
        val store = secureStore()
        val value = "Juan Pérez ñandú — 日本 €"

        store.put("test_key_a", value)

        assertEquals(value, store.get("test_key_a"))
    }

    @Test
    fun `a setting is read back replaced and removed`() {
        val store = settingsStore()

        store.put("test_key_a", "http://192.168.1.20:8081")
        assertEquals("http://192.168.1.20:8081", store.get("test_key_a"))

        store.remove("test_key_a")
        assertNull(store.get("test_key_a"))
    }

    @Test
    fun `a secret is not in the settings and a setting is not in the keychain`() {
        secureStore().put("test_key_a", "secret")

        assertNull(settingsStore().get("test_key_a"))
    }
}
