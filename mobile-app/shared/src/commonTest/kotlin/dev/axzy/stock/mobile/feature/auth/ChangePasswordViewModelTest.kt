package dev.axzy.stock.mobile.feature.auth

import dev.axzy.stock.domain.identity.PasswordRejection
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.errorJson
import dev.axzy.stock.mobile.core.network.sessionJson
import dev.axzy.stock.mobile.core.session.Session
import dev.axzy.stock.mobile.core.session.SessionState
import dev.axzy.stock.mobile.core.storage.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChangePasswordViewModelTest {

    private val api = TestApi()
    private val session = Session(api.client, MemoryStore())
    // Lazy: the ViewModel takes its scope from Dispatchers.Main, which `setUp` replaces first.
    private val viewModel by lazy { ChangePasswordViewModel(session, username = "jperez") }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun signIn() {
        api.answer("POST", "/v1/auth/login", body = sessionJson("access-1", "refresh-1"))
        session.signIn("jperez", "the old password")
    }

    private fun type(current: String = "the old password", new: String, confirm: String = new) {
        viewModel.onCurrentChanged(current)
        viewModel.onNewChanged(new)
        viewModel.onConfirmChanged(confirm)
    }

    @Test
    fun `nothing is sent until the three fields are filled`() {
        viewModel.onCurrentChanged("the old password")
        viewModel.onNewChanged("a much better password 42")
        viewModel.submit()

        assertFalse(viewModel.uiState.value.canSubmit)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `two different copies of the new password are refused before anything is sent`() {
        type(new = "a much better password 42", confirm = "a much better password 43")

        viewModel.submit()

        assertEquals(PasswordError.Mismatch, viewModel.uiState.value.error)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a password the domain's policy refuses is refused with its first reason and nothing is sent`() {
        val cases = mapOf(
            "short1" to PasswordRejection.TOO_SHORT,
            "administrador" to PasswordRejection.COMMON,
            "abcdefghijkl" to PasswordRejection.COMMON,
            "jperez-jperez-2026" to PasswordRejection.CONTAINS_USERNAME,
            "onlyletterslongenough" to PasswordRejection.TOO_SIMPLE,
        )

        cases.forEach { (password, expected) ->
            type(new = password)
            viewModel.submit()
            assertEquals(PasswordError.Weak(expected), viewModel.uiState.value.error, password)
        }
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an accepted change is sent and the session ends because the API closed them all`() = runTest {
        signIn()
        api.answer("POST", "/v1/auth/change-password", status = 204)
        type(new = "a much better password 42")

        viewModel.submit()
        session.state.first { it is SessionState.SignedOut }

        assertEquals(SessionState.SignedOut(passwordChanged = true), session.state.value)
        assertEquals(
            """{"currentPassword":"the old password","newPassword":"a much better password 42"}""",
            api.callsTo("POST", "/v1/auth/change-password").single().body,
        )
    }

    @Test
    fun `a change the API refuses is reported by its code and the form stays filled`() = runTest {
        signIn()
        api.answer("POST", "/v1/auth/change-password", status = 401, body = errorJson("INVALID_CREDENTIALS"))
        type(current = "wrong", new = "a much better password 42")

        viewModel.submit()

        val state = viewModel.uiState.first { it.error != null }
        assertEquals(PasswordError.Failed(ApiFailure.Rejected(401, "INVALID_CREDENTIALS")), state.error)
        assertFalse(state.isSubmitting)
        assertEquals("a much better password 42", state.new)
    }

    @Test
    fun `typing again clears the error`() {
        type(new = "short1")
        viewModel.submit()

        viewModel.onNewChanged("a much better password 42")

        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `the state never prints a password`() {
        type(current = "secret-current-pw", new = "secret-new-password-1")

        val text = viewModel.uiState.value.toString()
        assertFalse("secret" in text)
    }
}
