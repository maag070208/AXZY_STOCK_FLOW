package dev.axzy.stock.mobile.core.session

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.mobile.core.network.ApiClient
import dev.axzy.stock.mobile.core.network.ApiFailure
import dev.axzy.stock.mobile.core.network.ApiResult
import dev.axzy.stock.mobile.core.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the app knows about the session. The app shows the screen that matches. */
sealed interface SessionState {

    /** A refresh token was stored by an earlier launch; the API has not yet said whether it still works. */
    data object Restoring : SessionState

    /** Nobody is signed in. [passwordChanged] is true right after a password change, which closes every session. */
    data class SignedOut(val passwordChanged: Boolean = false) : SessionState

    /**
     * Signed in, but the account must change its password before anything else: until it does, the API refuses every
     * route except the change itself, sign-out and `me`.
     */
    data class MustChangePassword(val user: SessionUser) : SessionState

    data class SignedIn(val user: SessionUser) : SessionState
}

/**
 * The session of the operator: sign-in, renewal, password change and sign-out against the API.
 *
 * **What is kept on the device is the refresh token, in [store] (secure storage), and nothing else.** The access token
 * lives in memory only; when the app opens, [restore] trades the stored refresh token for a new pair. The API rotates
 * the refresh token on every use, so each new one replaces the stored one.
 */
class Session(private val api: ApiClient, private val store: KeyValueStore) {

    private class Tokens(val access: String, val refresh: String)

    private val _state = MutableStateFlow<SessionState>(
        if (store.get(REFRESH_TOKEN) != null) SessionState.Restoring else SessionState.SignedOut(),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var tokens: Tokens? = null
    private val renewal = Mutex()

    init {
        api.accessToken = { tokens?.access }
        api.renewSession = ::renew
    }

    /**
     * Opens the session the previous launch left, if the API still accepts its refresh token. A refusal clears it;
     * no answer leaves it for the next launch, so a phone with no signal does not lose its session.
     */
    suspend fun restore() {
        if (_state.value != SessionState.Restoring) return
        val stored = store.get(REFRESH_TOKEN) ?: return close()
        when (val result = refresh(stored)) {
            is Either.Right -> accept(result.value)
            is Either.Left -> if (result.error is ApiFailure.Rejected) close() else _state.value = SessionState.SignedOut()
        }
    }

    suspend fun signIn(username: String, password: String): ApiResult<SessionUser> {
        val request = LoginRequest(username = username.trim(), password = password)
        return when (val result = api.post<LoginRequest, SessionDto>(LOGIN, request, authenticated = false)) {
            is Either.Left -> result
            is Either.Right -> Either.Right(accept(result.value))
        }
    }

    /**
     * Changes the password. The API closes **every** session when it does, this one included, so on success the
     * session ends here too and the operator signs in again with the new password.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> {
        val result = api.postNoContent(CHANGE_PASSWORD, ChangePasswordRequest(currentPassword, newPassword))
        if (result is Either.Right) close(passwordChanged = true)
        return result
    }

    /** Closes the session here at once, and tells the API so the refresh token stops working too. */
    suspend fun signOut() {
        val refreshToken = tokens?.refresh ?: store.get(REFRESH_TOKEN)
        close()
        if (refreshToken != null) api.postNoContent(LOGOUT, RefreshRequest(refreshToken), authenticated = false)
    }

    private suspend fun refresh(refreshToken: String): ApiResult<SessionDto> =
        api.post<RefreshRequest, SessionDto>(REFRESH, RefreshRequest(refreshToken), authenticated = false)

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

        when (val result = refresh(current.refresh)) {
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
        store.put(REFRESH_TOKEN, session.refreshToken)
        _state.value = if (session.user.mustChangePassword) SessionState.MustChangePassword(user) else SessionState.SignedIn(user)
        return user
    }

    private fun close(passwordChanged: Boolean = false) {
        tokens = null
        store.remove(REFRESH_TOKEN)
        _state.value = SessionState.SignedOut(passwordChanged)
    }

    private companion object {
        const val REFRESH_TOKEN = "refresh_token"
        const val LOGIN = "/v1/auth/login"
        const val REFRESH = "/v1/auth/refresh"
        const val LOGOUT = "/v1/auth/logout"
        const val CHANGE_PASSWORD = "/v1/auth/change-password"
    }
}
