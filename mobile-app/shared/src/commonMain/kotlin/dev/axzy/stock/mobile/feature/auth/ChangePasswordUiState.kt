package dev.axzy.stock.mobile.feature.auth

import dev.axzy.stock.domain.identity.PasswordRejection
import dev.axzy.stock.mobile.core.network.ApiFailure

/** Why a password change was not made. */
sealed interface PasswordError {
    /** The two copies of the new password differ. Nothing was sent. */
    data object Mismatch : PasswordError

    /** The domain's policy refuses the new password. Nothing was sent. */
    data class Weak(val rejection: PasswordRejection) : PasswordError

    /** The API did not make the change: it refused it, or it did not answer. */
    data class Failed(val failure: ApiFailure) : PasswordError
}

/** What the password change screen shows. */
data class ChangePasswordUiState(
    val current: String = "",
    val new: String = "",
    val confirm: String = "",
    val isSubmitting: Boolean = false,
    val error: PasswordError? = null,
) {
    val canSubmit: Boolean get() = current.isNotEmpty() && new.isNotEmpty() && confirm.isNotEmpty() && !isSubmitting

    /** The generated text would print the passwords wherever the state is logged. */
    override fun toString(): String = "ChangePasswordUiState(passwords=<hidden>, isSubmitting=$isSubmitting, error=$error)"
}
