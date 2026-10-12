package dev.axzy.stock.mobile.core.session

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.errorJson
import dev.axzy.stock.mobile.core.network.sessionJson
import dev.axzy.stock.mobile.core.storage.MemoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionTest {

    @Serializable
    private data class Thing(val id: Long)

    private val api = TestApi()
    private val store = MemoryStore()
    private val session = Session(api.client, store)

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

        assertEquals(Either.Left(ApiFailure.Rejected(401, "INVALID_CREDENTIALS")), result)
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.get("refresh_token"))
    }

    @Test
    fun `an account that must change its password is signed in as such with the token to do it and is not let into the app`() = runTest {
        api.answer("POST", "/v1/auth/login", body = sessionJson("access-1", "refresh-1", mustChangePassword = true))
        api.answer("POST", "/v1/auth/change-password", status = 204)

        val user = assertIs<Either.Right<SessionUser>>(session.signIn("admin", "bootstrap")).value
        assertEquals(SessionState.MustChangePassword(user), session.state.value)

        session.changePassword("bootstrap", "a much better password 42")

        assertEquals("Bearer access-1", api.callsTo("POST", "/v1/auth/change-password").single().authorization)
        assertEquals(SessionState.SignedOut(passwordChanged = true), session.state.value)
    }

    @Test
    fun `a password change is sent with the token and closes the session here because the API closes them all`() = runTest {
        signIn()
        api.answer("POST", "/v1/auth/change-password", status = 204)

        val result = session.changePassword("the password", "a much better password 42")

        assertEquals(Either.Right(Unit), result)
        assertEquals("""{"currentPassword":"the password","newPassword":"a much better password 42"}""", api.calls.last().body)
        assertEquals("Bearer access-1", api.calls.last().authorization)
        assertEquals(SessionState.SignedOut(passwordChanged = true), session.state.value)
        assertNull(store.get("refresh_token"))
        // The API already revoked the refresh token: asking it to again would be noise.
        assertEquals(0, api.callsTo("POST", "/v1/auth/logout").size)
    }

    @Test
    fun `a password change the API refuses keeps the session`() = runTest {
        signIn()
        api.answer("POST", "/v1/auth/change-password", status = 401, body = errorJson("INVALID_CREDENTIALS"))

        val result = session.changePassword("wrong", "a much better password 42")

        assertEquals(Either.Left(ApiFailure.Rejected(401, "INVALID_CREDENTIALS")), result)
        assertIs<SessionState.SignedIn>(session.state.value)
        assertEquals("refresh-1", store.get("refresh_token"))
    }

    @Test
    fun `only the refresh token is kept on the device and each renewal replaces it`() = runTest {
        signIn()
        assertEquals("refresh-1", store.get("refresh_token"))
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("GET", "/v1/things", body = """{"id":1}""")
        api.answer("POST", "/v1/auth/refresh", body = sessionJson(access = "access-2", refresh = "refresh-2"))

        api.client.get<Thing>("/v1/things")

        assertEquals("refresh-2", store.get("refresh_token"))
    }

    @Test
    fun `a launch with a stored refresh token starts restoring and one without starts signed out`() {
        assertEquals(SessionState.SignedOut(), session.state.value)

        store.put("refresh_token", "refresh-1")

        assertEquals(SessionState.Restoring, Session(api.client, store).state.value)
    }

    @Test
    fun `restoring trades the stored token for a session and keeps the rotated token`() = runTest {
        store.put("refresh_token", "refresh-1")
        val restoring = Session(api.client, store)
        api.answer("POST", "/v1/auth/refresh", body = sessionJson(access = "access-2", refresh = "refresh-2"))
        api.answer("GET", "/v1/things", body = """{"id":1}""")

        restoring.restore()
        api.client.get<Thing>("/v1/things")

        assertIs<SessionState.SignedIn>(restoring.state.value)
        assertEquals("""{"refreshToken":"refresh-1"}""", api.callsTo("POST", "/v1/auth/refresh").single().body)
        assertEquals("refresh-2", store.get("refresh_token"))
        assertEquals("Bearer access-2", api.calls.last().authorization)
    }

    @Test
    fun `a stored token the API refuses is cleared and the operator signs in`() = runTest {
        store.put("refresh_token", "refresh-1")
        val restoring = Session(api.client, store)
        api.answer("POST", "/v1/auth/refresh", status = 401, body = errorJson("TOKEN_REUSED"))

        restoring.restore()

        assertEquals(SessionState.SignedOut(), restoring.state.value)
        assertNull(store.get("refresh_token"))
    }

    @Test
    fun `with no network the stored token stays for the next launch and the operator can still sign in`() = runTest {
        store.put("refresh_token", "refresh-1")
        val restoring = Session(api.client, store)
        api.cutOff("POST", "/v1/auth/refresh")

        restoring.restore()

        assertEquals(SessionState.SignedOut(), restoring.state.value)
        assertEquals("refresh-1", store.get("refresh_token"))
    }

    @Test
    fun `restoring does nothing when no session was waiting to be restored`() = runTest {
        session.restore()

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertEquals(0, api.calls.size)
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
    fun `a renewal the API refuses closes the session and clears the stored token`() = runTest {
        signIn()
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.answer("POST", "/v1/auth/refresh", status = 401, body = errorJson("TOKEN_REUSED"))

        val result = api.client.get<Thing>("/v1/things")

        assertEquals(Either.Left(ApiFailure.Rejected(401, "UNAUTHENTICATED")), result)
        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.get("refresh_token"))
    }

    @Test
    fun `a renewal that gets no answer keeps the session because the network may come back`() = runTest {
        signIn()
        api.answer("GET", "/v1/things", status = 401, body = errorJson("UNAUTHENTICATED"))
        api.cutOff("POST", "/v1/auth/refresh")

        api.client.get<Thing>("/v1/things")

        assertIs<SessionState.SignedIn>(session.state.value)
        assertEquals("refresh-1", store.get("refresh_token"))
    }

    @Test
    fun `signing out closes the session clears the device and revokes its refresh token at the API`() = runTest {
        signIn()
        api.answer("POST", "/v1/auth/logout", status = 204)
        api.answer("GET", "/v1/things", body = """{"id":1}""")

        session.signOut()
        api.client.get<Thing>("/v1/things")

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.get("refresh_token"))
        assertEquals("""{"refreshToken":"refresh-1"}""", api.callsTo("POST", "/v1/auth/logout").single().body)
        assertNull(api.calls.last().authorization)
    }

    @Test
    fun `signing out closes the session even when the API cannot be reached`() = runTest {
        signIn()
        api.cutOff("POST", "/v1/auth/logout")

        session.signOut()

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.get("refresh_token"))
    }
}
