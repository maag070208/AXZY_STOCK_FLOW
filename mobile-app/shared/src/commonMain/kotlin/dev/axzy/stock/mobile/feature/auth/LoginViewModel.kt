package dev.axzy.stock.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.mobile.core.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the sign-in. It hands the credentials to the session and keeps nothing of them: once the session opens,
 * the form is emptied, and after a failed try the password is cleared so it does not stay on screen.
 */
class LoginViewModel(private val session: Session) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(username: String) = _uiState.update { it.copy(username = username, error = null) }

    fun onPasswordChanged(password: String) = _uiState.update { it.copy(password = password, error = null) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = session.signIn(state.username, state.password)) {
                is Either.Right -> _uiState.value = LoginUiState()
                is Either.Left -> _uiState.update { it.copy(password = "", isSubmitting = false, error = result.error) }
            }
        }
    }
}
