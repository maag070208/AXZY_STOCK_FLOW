package dev.axzy.stock.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.axzy.stock.mobile.core.network.ApiClient
import dev.axzy.stock.mobile.core.network.defaultApiBaseUrl
import dev.axzy.stock.mobile.core.session.Session
import dev.axzy.stock.mobile.core.session.SessionState
import dev.axzy.stock.mobile.core.theme.StockFlowTheme
import dev.axzy.stock.mobile.feature.auth.LoginScreen
import dev.axzy.stock.mobile.feature.auth.LoginViewModel
import kotlinx.coroutines.launch

/**
 * The root of the app: the theme, the one API client, the session, and the choice the session makes between the
 * sign-in screen and the sections. Nothing behind the sign-in is composed while nobody is signed in.
 */
@Composable
fun App() {
    StockFlowTheme {
        val api = remember { ApiClient(defaultApiBaseUrl) }
        val session = remember { Session(api) }
        val state by session.state.collectAsState()
        val scope = rememberCoroutineScope()

        when (val current = state) {
            SessionState.SignedOut -> LoginScreen(viewModel = viewModel { LoginViewModel(session) })

            // Keyed by the user: another person signing in starts from nothing the previous one loaded.
            is SessionState.SignedIn -> key(current.user.id) {
                AppShell(user = current.user, api = api, onSignOut = { scope.launch { session.signOut() } })
            }
        }
    }
}
