package dev.axzy.stock.mobile.core.session

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.errorJson
import dev.axzy.stock.mobile.core.network.sessionJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionTest {

    @Serializable
    private data class Thing(val id: Long)

    private val api = TestApi()
    private val session = Session(api.client)

    private suspend fun signIn() {
        api.answer("POST", "/v1/auth/login", body = sessionJson(access = "access-1", refresh = "refresh-1"))
        session.signIn("jperez", "the password")
    }

    @Test
    fun `signing in opens the session with the user and sends its token on the calls that follow`() = runTest {
        api.answer("POST", "/v1/auth/login", body = sessionJson(access = "access-1", refresh = "refresh-1"))
        api.answer("GET", "/v1/things", body = """{"id":1}""")

        val result = session.signIn("  jperez ", "the password")
        api.client.get<Thing>("/v1/things")

        val user = assertIs<Either.Right<SessionUser>>(result).value
        assertEquals(SessionUser(7, "jperez", "Juan Pérez", setOf("invoice.view", "invoice.capture")), user)
        assertEquals(SessionState.SignedIn(user), session.state.value)
        assertEquals("""{"username":"jperez","password":"the password"}""", api.calls.first().body)
        assertEquals("Bearer access-1", api.calls.last().authorization)
    }

    @Test
    fun `a refused sign-in leaves the session closed and returns the API's failure`() = runTest {
        api.answer("POST", "/v1/auth/login", status = 401, body = errorJson("INVALID_CREDENTIALS"))

        val result = session.signIn("jperez", "wrong")

        assertEquals(Either.Left(SignInFailure.Api(ApiFailure.Rejected(401, "INVALID_CREDENTIALS"))), result)
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `an account that must change its password is not signed in, and the session the API opened is closed`() = runTest {
        api.answer("POST", "/v1/auth/login", body = sessionJson("access-1", "refresh-1", mustChangePassword = true))
        api.answer("POST", "/v1/auth/logout", status = 204)

        val result = session.signIn("admin", "bootstrap")

        assertEquals(Either.Left(SignInFailure.PasswordChangeRequired), result)
        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals("""{"refreshToken":"refresh-1"}""", api.callsTo("POST", "/v1/auth/logout").single().body)
    }

    @Test
    fun `a token the API no longer accepts is traded for a new one and the call goes through`() = runTest {
        signIn()
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("GET", "/v1/things", body = """{"id":1}""")
        api.answer("POST", "/v1/auth/refresh", body = sessionJson(access = "access-2", refresh = "refresh-2"))

        val result = api.client.get<Thing>("/v1/things")

        assertEquals(Either.Right(Thing(1)), result)
        assertEquals("""{"refreshToken":"refresh-1"}""", api.callsTo("POST", "/v1/auth/refresh").single().body)
        assertEquals(listOf("Bearer access-1", "Bearer access-2"), api.callsTo("GET", "/v1/things").map { it.authorization })
        assertIs<SessionState.SignedIn>(session.state.value)
    }

    @Test
    fun `two calls refused together renew the session only once`() = runTest {
        signIn()
        // Using the same refresh token twice would make the API close every session of the family.
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("GET", "/v1/things", body = """{"id":1}""")
        api.answer("POST", "/v1/auth/refresh", body = sessionJson(access = "access-2", refresh = "refresh-2"))

        val results = listOf(
            async { api.client.get<Thing>("/v1/things") },
            async { api.client.get<Thing>("/v1/things") },
        ).awaitAll()

        assertEquals(listOf<Any>(Either.Right(Thing(1)), Either.Right(Thing(1))), results)
        assertEquals(1, api.callsTo("POST", "/v1/auth/refresh").size)
    }

    @Test
    fun `a renewal the API refuses closes the session`() = runTest {
        signIn()
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("POST", "/v1/auth/refresh", status = 401, body = errorJson("TOKEN_REUSED"))

        val result = api.client.get<Thing>("/v1/things")

        assertEquals(Either.Left(ApiFailure.Rejected(401, "UNAUTHENTICATED")), result)
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `a renewal that gets no answer keeps the session, because the network may come back`() = runTest {
        signIn()
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.cutOff("POST", "/v1/auth/refresh")

        api.client.get<Thing>("/v1/things")

        assertIs<SessionState.SignedIn>(session.state.value)
    }

    @Test
    fun `signing out closes the session and revokes its refresh token at the API`() = runTest {
        signIn()
        api.answer("POST", "/v1/auth/logout", status = 204)
        api.answer("GET", "/v1/things", body = """{"id":1}""")

        session.signOut()
        api.client.get<Thing>("/v1/things")

        assertEquals(SessionState.SignedOut, session.state.value)
        assertEquals("""{"refreshToken":"refresh-1"}""", api.callsTo("POST", "/v1/auth/logout").single().body)
        assertEquals(null, api.calls.last().authorization)
    }

    @Test
    fun `signing out closes the session even when the API cannot be reached`() = runTest {
        signIn()
        api.cutOff("POST", "/v1/auth/logout")

        session.signOut()

        assertEquals(SessionState.SignedOut, session.state.value)
    }
}
