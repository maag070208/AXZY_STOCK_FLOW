package dev.axzy.stock.mobile.feature.auth

import dev.axzy.stock.mobile.core.network.ApiAddress
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
class LoginViewModelTest {

    private val api = TestApi()
    private val session = Session(api.client, MemoryStore())
    private val settings = MemoryStore()
    private val address = ApiAddress(settings, default = "http://10.0.2.2:8081")
    private val viewModel by lazy { LoginViewModel(session, address) }

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

        assertEquals(LoginUiState(server = "http://10.0.2.2:8081"), viewModel.uiState.first { !it.isSubmitting })
    }

    @Test
    fun `a failed sign-in keeps the user clears the password and reports the failure`() = runTest {
        api.answer("POST", "/v1/auth/login", status = 401, body = errorJson("ACCOUNT_LOCKED"))
        type("jperez", "wrong")

        viewModel.submit()
        val state = viewModel.uiState.first { it.error != null }

        assertEquals("jperez", state.username)
        assertEquals("", state.password)
        assertFalse(state.isSubmitting)
        assertEquals(LoginError.Failed(ApiFailure.Rejected(401, "ACCOUNT_LOCKED")), state.error)
        assertEquals(SessionState.SignedOut(), session.state.value)
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
    }

    @Test
    fun `the screen starts with the server in use and hides the field until asked`() {
        assertEquals("http://10.0.2.2:8081", viewModel.uiState.value.server)
        assertFalse(viewModel.uiState.value.serverVisible)

        viewModel.onServerToggled()
        assertTrue(viewModel.uiState.value.serverVisible)
    }

    @Test
    fun `a server typed by the operator is kept before the sign-in goes to it`() = runTest {
        api.answer("POST", "/v1/auth/login", status = 401, body = errorJson("INVALID_CREDENTIALS"))
        type("jperez", "wrong")
        viewModel.onServerChanged(" http://192.168.1.20:8081/ ")

        viewModel.submit()
        viewModel.uiState.first { it.error != null }

        assertEquals("http://192.168.1.20:8081", address.url)
        assertEquals("http://192.168.1.20:8081", viewModel.uiState.value.server)
    }

    @Test
    fun `a server that is not an address is refused shown and nothing is sent`() {
        type("jperez", "the password")
        viewModel.onServerChanged("192.168.1.20")

        viewModel.submit()

        val state = viewModel.uiState.value
        assertEquals(LoginError.InvalidServer, state.error)
        assertTrue(state.serverVisible)
        assertTrue(api.calls.isEmpty())
        assertEquals("http://10.0.2.2:8081", address.url)
    }
}
