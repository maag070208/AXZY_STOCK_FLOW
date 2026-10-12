package dev.axzy.stock.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.axzy.stock.mobile.core.network.ApiAddress
import dev.axzy.stock.mobile.core.network.ApiClient
import dev.axzy.stock.mobile.core.session.Session
import dev.axzy.stock.mobile.core.session.SessionState
import dev.axzy.stock.mobile.core.storage.secureStore
import dev.axzy.stock.mobile.core.storage.settingsStore
import dev.axzy.stock.mobile.core.theme.StockFlowTheme
import dev.axzy.stock.mobile.core.ui.ITLoadingState
import dev.axzy.stock.mobile.feature.auth.ChangePasswordScreen
import dev.axzy.stock.mobile.feature.auth.ChangePasswordViewModel
import dev.axzy.stock.mobile.feature.auth.LoginScreen
import dev.axzy.stock.mobile.feature.auth.LoginViewModel
import kotlinx.coroutines.launch

/**
 * The root of the app: the theme, the one API client, the session, and the choice the session makes between the
 * sign-in screen, the forced password change and the sections. Nothing behind the sign-in is composed while nobody
 * is signed in.
 */
@Composable
fun App() {
    StockFlowTheme {
        val address = remember { ApiAddress(settingsStore()) }
        val api = remember { ApiClient(baseUrl = { address.url }) }
        val session = remember { Session(api, secureStore()) }
        val state by session.state.collectAsState()
        val scope = rememberCoroutineScope()

        // If the last launch left a session, the API decides whether it still stands.
        LaunchedEffect(session) { session.restore() }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val current = state) {
                SessionState.Restoring -> ITLoadingState()

                is SessionState.SignedOut -> LoginScreen(
                    viewModel = viewModel { LoginViewModel(session, address) },
                    passwordChanged = current.passwordChanged,
                )

                is SessionState.MustChangePassword -> ChangePasswordScreen(
                    viewModel = viewModel(key = "password-${current.user.id}") {
                        ChangePasswordViewModel(session, current.user.username)
                    },
                    required = true,
                    onBack = null,
                    onSignOut = { scope.launch { session.signOut() } },
                )

                // Keyed by the user: another person signing in starts from nothing the previous one loaded.
                is SessionState.SignedIn -> key(current.user.id) {
                    AppShell(
                        user = current.user,
                        api = api,
                        session = session,
                        onSignOut = { scope.launch { session.signOut() } },
                    )
                }
            }
        }
    }
}
