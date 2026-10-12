package dev.axzy.stock.mobile.core.network

import dev.axzy.stock.mobile.core.storage.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiAddressTest {

    private val store = MemoryStore()
    private val address = ApiAddress(store, default = "http://10.0.2.2:8081")

    @Test
    fun `until the operator chooses one the address is the platform's`() {
        assertEquals("http://10.0.2.2:8081", address.url)
    }

    @Test
    fun `an address is trimmed and loses its trailing slash and the next call uses it`() {
        assertTrue(address.save("  https://stock.example.com/  "))

        assertEquals("https://stock.example.com", address.url)
    }

    @Test
    fun `an address with a path is kept for an API behind a proxy`() {
        assertEquals("https://example.com/stock-api", ApiAddress.normalize("https://example.com/stock-api/"))
    }

    @Test
    fun `what cannot be an address is refused and the one in use stays`() {
        address.save("https://stock.example.com")

        listOf("", "   ", "stock.example.com", "ftp://stock.example.com", "http://", "https:///path", "http://a b", "http://host?x=1", "http://host#top")
            .forEach { bad ->
                assertNull(ApiAddress.normalize(bad), bad)
                assertFalse(address.save(bad), bad)
            }
        assertEquals("https://stock.example.com", address.url)
    }

    @Test
    fun `choosing the platform's own address again forgets the choice`() {
        address.save("https://stock.example.com")

        address.save("http://10.0.2.2:8081/")

        assertNull(store.get("api_base_url"))
        assertEquals("http://10.0.2.2:8081", address.url)
    }

    @Test
    fun `the scheme is accepted in any case`() {
        assertEquals("HTTPS://Host", ApiAddress.normalize("HTTPS://Host"))
    }
}
