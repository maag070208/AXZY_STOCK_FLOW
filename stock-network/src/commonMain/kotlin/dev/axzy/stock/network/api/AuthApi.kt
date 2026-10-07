package dev.axzy.stock.network.api

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.auth.AuthSessionDto
import dev.axzy.stock.network.dto.auth.ChangePasswordRequest
import dev.axzy.stock.network.dto.auth.LoginRequest
import dev.axzy.stock.network.dto.auth.LogoutRequest
import dev.axzy.stock.network.dto.auth.RefreshTokenRequest
import dev.axzy.stock.network.dto.auth.UserDto
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/auth` — sesión
 *
 * Contrato: CONTRATO_API.md §2.
 *
 * El login y el refresh **persisten la sesión** en el
 * [dev.axzy.stock.network.client.TokenStorePort] del cliente. Es lo que hace
 * que el refresh automático funcione sin que la pantalla de login tenga que
 * enterarse de nada.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AuthApi internal constructor(private val http: StockHttpClient) {

    /**
     * Inicia sesión y guarda la sesión.
     *
     * Errores que la UI debe distinguir **por `code`**: `INVALID_CREDENTIALS`,
     * `ACCOUNT_DISABLED`, `ACCOUNT_LOCKED` (con `details.until`) y
     * `RATE_LIMITED`.
     */
    suspend fun login(username: String, password: String): ApiOutcome<AuthSessionDto> {
        val outcome = http.postText(
            path = "/auth/login",
            body = stockJson.encodeToString(LoginRequest(username, password)),
            // El login es público: mandar un token viejo no aporta y confunde.
            authenticated = false,
        ).decode<AuthSessionDto>()

        if (outcome is Either.Right) http.storeSession(outcome.value)
        return outcome
    }

    /**
     * Rota el refresh token a mano.
     *
     * Normalmente **no hay que llamarlo**: el cliente refresca solo cuando
     * recibe un `401`. Existe para el arranque en frío, donde conviene canjear
     * el refresh antes de la primera petición en lugar de esperar a que falle.
     */
    suspend fun refresh(refreshToken: String): ApiOutcome<AuthSessionDto> {
        val outcome = http.postText(
            path = "/auth/refresh",
            body = stockJson.encodeToString(RefreshTokenRequest(refreshToken)),
            authenticated = false,
        ).decode<AuthSessionDto>()

        if (outcome is Either.Right) http.storeSession(outcome.value)
        return outcome
    }

    /** Reconstruye los permisos de la sesión actual sin volver a autenticar. */
    suspend fun me(): ApiOutcome<UserDto> = http.getText("/auth/me").decode()

    /**
     * Cierra sesión: revoca el refresh token en el servidor **y** limpia el
     * almacén local.
     *
     * La limpieza local es incondicional. Si el servidor no responde, el token
     * de este dispositivo se borra igual: dejar la sesión viva por un fallo de
     * red es lo contrario de lo que el usuario pidió al cerrar sesión.
     */
    suspend fun logout(): ApiOutcome<Unit> {
        val refreshToken = http.currentSession()?.refreshToken
        val outcome = if (refreshToken == null) {
            // Sin refresh token no hay nada que revocar: no se llama al servidor.
            Unit.asRight()
        } else {
            http.postNoContent(
                path = "/auth/logout",
                body = stockJson.encodeToString(LogoutRequest(refreshToken)),
            )
        }
        http.clearSession()
        return outcome
    }

    /**
     * Cambia la contraseña.
     *
     * El servidor responde `204` e **invalida todas las sesiones, incluida la
     * actual**, así que el cliente limpia su almacén y avisa a la app: quedarse
     * con el token haría que la siguiente pantalla fallara con un `401` sin
     * explicación.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): ApiOutcome<Unit> {
        val outcome = http.postNoContent(
            path = "/auth/change-password",
            body = stockJson.encodeToString(ChangePasswordRequest(currentPassword, newPassword)),
        )
        if (outcome is Either.Right) http.invalidateSession()
        return outcome
    }

    /** Consulta de comodidad para la pantalla de configuración. */
    suspend fun hasStoredSession(): Boolean = http.currentSession() != null
}
