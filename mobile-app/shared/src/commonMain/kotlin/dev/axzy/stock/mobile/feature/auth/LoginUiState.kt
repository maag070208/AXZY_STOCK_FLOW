package dev.axzy.stock.mobile.feature.auth

import dev.axzy.stock.mobile.core.session.SignInFailure

/** What the sign-in screen shows: what was typed, whether the API is being asked, and why the last try failed. */
data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: SignInFailure? = null,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSubmitting

    /** The generated text would print the password wherever the state is logged. */
    override fun toString(): String =
        "LoginUiState(username=$username, password=<hidden>, isSubmitting=$isSubmitting, error=$error)"
}
