package dev.axzy.stock.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.mobile.core.network.ApiAddress
import dev.axzy.stock.mobile.core.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the sign-in. It keeps the server address the operator chose, hands the credentials to the session and keeps
 * nothing of them: once the session opens, the form is emptied, and after a failed try the password is cleared so it
 * does not stay on screen.
 */
class LoginViewModel(private val session: Session, private val address: ApiAddress) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState(server = address.url))
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(username: String) = _uiState.update { it.copy(username = username, error = null) }

    fun onPasswordChanged(password: String) = _uiState.update { it.copy(password = password, error = null) }

    fun onServerChanged(server: String) = _uiState.update { it.copy(server = server, error = null) }

    fun onServerToggled() = _uiState.update { it.copy(serverVisible = !it.serverVisible) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        // The address is checked and kept before the call, because the call goes to it.
        if (!address.save(state.server)) {
            _uiState.update { it.copy(serverVisible = true, error = LoginError.InvalidServer) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null, server = address.url) }
        viewModelScope.launch {
            when (val result = session.signIn(state.username, state.password)) {
                is Either.Right -> _uiState.value = LoginUiState(server = address.url)
                is Either.Left -> _uiState.update {
                    it.copy(password = "", isSubmitting = false, error = LoginError.Failed(result.error))
                }
            }
        }
    }
}
