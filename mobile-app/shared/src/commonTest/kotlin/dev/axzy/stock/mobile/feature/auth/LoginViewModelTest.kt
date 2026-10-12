package dev.axzy.stock.mobile.feature.auth

import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.TestApi
import dev.axzy.stock.mobile.core.network.errorJson
import dev.axzy.stock.mobile.core.network.sessionJson
import dev.axzy.stock.mobile.core.session.Session
import dev.axzy.stock.mobile.core.session.SessionState
import dev.axzy.stock.mobile.core.session.SignInFailure
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private val api = TestApi()
    private val session = Session(api.client)
    private val viewModel by lazy { LoginViewModel(session) }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun type(username: String, password: String) {
        viewModel.onUsernameChanged(username)
        viewModel.onPasswordChanged(password)
    }

    @Test
    fun `a correct sign-in opens the session and empties the form`() = runTest {
        api.answer("POST", "/v1/auth/login", body = sessionJson(access = "access-1", refresh = "refresh-1"))
        type("jperez", "the password")

        viewModel.submit()
        session.state.first { it is SessionState.SignedIn }

        assertEquals(LoginUiState(), viewModel.uiState.first { !it.isSubmitting })
    }

    @Test
    fun `a failed sign-in keeps the user, clears the password and reports the failure`() = runTest {
        api.answer("POST", "/v1/auth/login", status = 401, body = errorJson("ACCOUNT_LOCKED"))
        type("jperez", "wrong")

        viewModel.submit()
        val state = viewModel.uiState.first { it.error != null }

        assertEquals("jperez", state.username)
        assertEquals("", state.password)
        assertFalse(state.isSubmitting)
        assertEquals(SignInFailure.Api(ApiFailure.Rejected(401, "ACCOUNT_LOCKED")), state.error)
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun `typing again clears the failure of the last try`() = runTest {
        api.answer("POST", "/v1/auth/login", status = 401, body = errorJson("INVALID_CREDENTIALS"))
        type("jperez", "wrong")
        viewModel.submit()
        viewModel.uiState.first { it.error != null }

        viewModel.onPasswordChanged("a")

        assertEquals(null, viewModel.uiState.value.error)
    }

    @Test
    fun `nothing is sent while the user or the password is missing`() {
        type("   ", "the password")
        viewModel.submit()
        type("jperez", "")
        viewModel.submit()

        assertTrue(api.calls.isEmpty())
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `the state never prints the password`() {
        type("jperez", "the password")

        assertFalse("the password" in viewModel.uiState.value.toString())
        assertIs<String>(viewModel.uiState.value.toString())
    }
}
