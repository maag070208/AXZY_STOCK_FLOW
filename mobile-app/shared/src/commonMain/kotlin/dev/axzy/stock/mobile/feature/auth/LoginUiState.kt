package dev.axzy.stock.mobile.feature.auth

import dev.axzy.stock.mobile.core.network.ApiFailure

/** Why a sign-in did not open a session. */
sealed interface LoginError {
    /** The address of the server is not one the app can use. Nothing was sent. */
    data object InvalidServer : LoginError

    /** The API did not accept the sign-in, or did not answer. */
    data class Failed(val failure: ApiFailure) : LoginError
}

/**
 * What the sign-in screen shows: what was typed, the server the app will talk to, whether the API is being asked,
 * and why the last try failed.
 */
data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val server: String = "",
    /** The server field is hidden until the operator asks for it: most people never change it. */
    val serverVisible: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotEmpty() && !isSubmitting

    /** The generated text would print the password wherever the state is logged. */
    override fun toString(): String =
        "LoginUiState(username=$username, password=<hidden>, server=$server, isSubmitting=$isSubmitting, error=$error)"
}
