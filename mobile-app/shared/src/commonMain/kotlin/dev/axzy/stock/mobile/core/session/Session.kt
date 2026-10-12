package dev.axzy.stock.mobile.core.session

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.mobile.core.network.ApiClient
import dev.axzy.stock.mobile.core.network.ApiFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether somebody is signed in. The app shows the sign-in screen or its sections according to this. */
sealed interface SessionState {
    data object SignedOut : SessionState
    data class SignedIn(val user: SessionUser) : SessionState
}

/** Why a sign-in did not open a session. */
sealed interface SignInFailure {
    /** The API did not accept it, or did not answer. */
    data class Api(val failure: ApiFailure) : SignInFailure

    /**
     * The credentials are right, but the account must change its password first. Until it does, the API refuses
     * every other route, and the app has no screen for the change yet: it is done on the web.
     */
    data object PasswordChangeRequired : SignInFailure
}

/**
 * The session of the operator: sign-in, renewal and sign-out against the API.
 *
 * **The tokens live in memory only.** They are never written to disk, so closing the app closes the session.
 * Keeping the session between launches needs the system's secure storage (Keychain, Keystore), which the app does
 * not have yet; plain preferences are not an option for a token.
 */
class Session(private val api: ApiClient) {

    private class Tokens(val access: String, val refresh: String)

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var tokens: Tokens? = null
    private val renewal = Mutex()

    init {
        api.accessToken = { tokens?.access }
        api.renewSession = ::renew
    }

    suspend fun signIn(username: String, password: String): Either<SignInFailure, SessionUser> {
        val request = LoginRequest(username = username.trim(), password = password)
        return when (val result = api.post<LoginRequest, SessionDto>(LOGIN, request, authenticated = false)) {
            is Either.Left -> Either.Left(SignInFailure.Api(result.error))
            is Either.Right -> {
                val session = result.value
                if (session.user.mustChangePassword) {
                    // The API already opened a session for this sign-in: close it, the app cannot use it.
                    api.postNoContent(LOGOUT, RefreshRequest(session.refreshToken), authenticated = false)
                    Either.Left(SignInFailure.PasswordChangeRequired)
                } else {
                    Either.Right(accept(session))
                }
            }
        }
    }

    /** Closes the session here at once, and tells the API so the refresh token stops working too. */
    suspend fun signOut() {
        val current = tokens
        close()
        if (current != null) api.postNoContent(LOGOUT, RefreshRequest(current.refresh), authenticated = false)
    }

    /**
     * Trades the refresh token for new tokens after the API refused [refusedToken].
     *
     * The API rotates the refresh token and treats a second use of the old one as theft: it closes every session
     * of the family. Two requests refused at the same time must therefore renew **once**; the lock makes the
     * second one find the new token and reuse it.
     */
    private suspend fun renew(refusedToken: String): Boolean = renewal.withLock {
        val current = tokens ?: return@withLock false
        if (current.access != refusedToken) return@withLock true

        when (val result = api.post<RefreshRequest, SessionDto>(REFRESH, RefreshRequest(current.refresh), authenticated = false)) {
            is Either.Right -> {
                accept(result.value)
                true
            }
            is Either.Left -> {
                // A refused renewal ends the session. One that got no answer does not: the network may come back.
                if (result.error is ApiFailure.Rejected) close()
                false
            }
        }
    }

    private fun accept(session: SessionDto): SessionUser {
        val user = session.user.toSessionUser()
        tokens = Tokens(access = session.accessToken, refresh = session.refreshToken)
        _state.value = SessionState.SignedIn(user)
        return user
    }

    private fun close() {
        tokens = null
        _state.value = SessionState.SignedOut
    }

    private companion object {
        const val LOGIN = "/v1/auth/login"
        const val REFRESH = "/v1/auth/refresh"
        const val LOGOUT = "/v1/auth/logout"
    }
}
