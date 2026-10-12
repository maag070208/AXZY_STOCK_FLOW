package dev.axzy.stock.mobile.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordValidation
import dev.axzy.stock.mobile.core.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the password change. The new password is checked here against the domain's own policy, so a weak one is
 * refused at once and without network; the API checks it again and has the last word. When the change is made the
 * API closes every session, this one included, and the session sends the app back to sign-in: nothing is left to
 * do on this screen.
 */
class ChangePasswordViewModel(private val session: Session, private val username: String) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = _uiState.asStateFlow()

    fun onCurrentChanged(value: String) = _uiState.update { it.copy(current = value, error = null) }

    fun onNewChanged(value: String) = _uiState.update { it.copy(new = value, error = null) }

    fun onConfirmChanged(value: String) = _uiState.update { it.copy(confirm = value, error = null) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        if (state.new != state.confirm) {
            _uiState.update { it.copy(error = PasswordError.Mismatch) }
            return
        }
        val validation = PasswordPolicy.validate(state.new, username)
        if (validation is PasswordValidation.Invalid) {
            // One reason at a time reads better than a list: the first is the one to fix first.
            _uiState.update { it.copy(error = PasswordError.Weak(validation.rejections.first())) }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            when (val result = session.changePassword(state.current, state.new)) {
                is Either.Right -> Unit
                is Either.Left -> _uiState.update {
                    it.copy(isSubmitting = false, error = PasswordError.Failed(result.error))
                }
            }
        }
    }
}
