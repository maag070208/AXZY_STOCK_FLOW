package dev.axzy.stock.mobile.core.network

import dev.axzy.stock.domain.common.Either
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiClientTest {

    @Serializable
    private data class Thing(val id: Long, val name: String)

    private val api = TestApi()

    @Test
    fun `a successful answer is decoded and the request carries the channel and the session's token`() = runTest {
        api.client.accessToken = { "token-1" }
        api.answer("GET", "/v1/things/1", body = """{"id":1,"name":"clamp","addedLater":true}""")

        val result = api.client.get<Thing>("/v1/things/1")

        assertEquals(Either.Right(Thing(1, "clamp")), result)
        assertEquals("MOBILE", api.calls.single().channel)
        assertEquals("Bearer token-1", api.calls.single().authorization)
    }

    @Test
    fun `a call that asks for no session sends no token even when there is one`() = runTest {
        api.client.accessToken = { "token-1" }
        api.answer("POST", "/v1/auth/login", body = """{"id":1,"name":"x"}""")

        api.client.post<Thing, Thing>("/v1/auth/login", Thing(1, "x"), authenticated = false)

        assertNull(api.calls.single().authorization)
        assertEquals("""{"id":1,"name":"x"}""", api.calls.single().body)
    }

    @Test
    fun `a refused call returns the API's code and details with its status`() = runTest {
        api.answer("GET", "/v1/things/1", status = 422, body = errorJson("INVOICE_HAS_PENDING_LINES", "\"pendingLines\":\"4\""))

        val result = api.client.get<Thing>("/v1/things/1")

        assertEquals(
            Either.Left(ApiFailure.Rejected(422, "INVOICE_HAS_PENDING_LINES", mapOf("pendingLines" to "4"))),
            result,
        )
    }

    @Test
    fun `an error whose body is not the contract's is an unexpected answer`() = runTest {
        api.answer("GET", "/v1/things/1", status = 502, body = "<html>Bad Gateway</html>")

        assertEquals(Either.Left(ApiFailure.Unexpected), api.client.get<Thing>("/v1/things/1"))
    }

    @Test
    fun `a success whose body does not decode is an unexpected answer`() = runTest {
        api.answer("GET", "/v1/things/1", body = """{"id":"not a number"}""")

        assertEquals(Either.Left(ApiFailure.Unexpected), api.client.get<Thing>("/v1/things/1"))
    }

    @Test
    fun `a call that gets no answer is unreachable`() = runTest {
        api.cutOff("GET", "/v1/things/1")

        assertEquals(Either.Left(ApiFailure.Unreachable), api.client.get<Thing>("/v1/things/1"))
    }

    @Test
    fun `a refused token is renewed once and the call is sent again with the new one`() = runTest {
        var token = "old"
        val refused = mutableListOf<String>()
        api.client.accessToken = { token }
        api.client.renewSession = { refusedToken ->
            refused += refusedToken
            token = "new"
            true
        }
        api.answer("GET", "/v1/things/1", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("GET", "/v1/things/1", body = """{"id":1,"name":"clamp"}""")

        val result = api.client.get<Thing>("/v1/things/1")

        assertEquals(Either.Right(Thing(1, "clamp")), result)
        assertEquals(listOf("old"), refused)
        assertEquals(listOf("Bearer old", "Bearer new"), api.calls.map { it.authorization })
    }

    @Test
    fun `when the session cannot be renewed the refusal is returned and nothing is sent again`() = runTest {
        api.client.accessToken = { "old" }
        api.client.renewSession = { false }
        api.answer("GET", "/v1/things/1", status = 401, body = errorJson("UNAUTHENTICATED"))

        val result = api.client.get<Thing>("/v1/things/1")

        assertEquals(Either.Left(ApiFailure.Rejected(401, "UNAUTHENTICATED")), result)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `a 401 that is not about the token like a wrong password does not renew the session`() = runTest {
        var renewals = 0
        api.client.accessToken = { "token-1" }
        api.client.renewSession = { renewals++; true }
        api.answer("POST", "/v1/auth/change-password", status = 401, body = errorJson("INVALID_CREDENTIALS"))

        val result = api.client.postNoContent("/v1/auth/change-password", Thing(1, "x"))

        assertEquals(Either.Left(ApiFailure.Rejected(401, "INVALID_CREDENTIALS")), result)
        assertEquals(0, renewals)
    }

    @Test
    fun `an answer without a body is a success for a call that expects none`() = runTest {
        api.answer("POST", "/v1/auth/logout", status = 204)

        assertEquals(Either.Right(Unit), api.client.postNoContent("/v1/auth/logout", Thing(1, "x"), authenticated = false))
    }
}
