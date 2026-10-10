package dev.axzy.stock.adapters.web.identity

import dev.axzy.stock.adapters.web.http.AuthenticatedOnly
import dev.axzy.stock.adapters.web.http.PublicRoute
import dev.axzy.stock.adapters.web.http.toNoContent
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.adapters.web.security.AllowedWithPendingPasswordChange
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.AuthSession
import dev.axzy.stock.application.identity.ChangePassword
import dev.axzy.stock.application.identity.Login
import dev.axzy.stock.application.identity.Logout
import dev.axzy.stock.application.identity.RefreshSession
import dev.axzy.stock.application.identity.SessionUser
import dev.axzy.stock.application.shared.RequestOrigin
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// The fields are optional so that their absence is explained by the use case (`FIELD_REQUIRED`) and not by
// a generic JSON read error.
data class LoginRequest(val username: String? = null, val password: String? = null)

data class RefreshRequest(val refreshToken: String? = null)

data class ChangePasswordRequest(val currentPassword: String? = null, val newPassword: String? = null)

data class SessionUserDto(
    val id: Long,
    val username: String,
    val name: String,
    val roles: List<String>,
    val permissions: List<String>,
    val scopes: Map<String, String>,
    val warehouses: List<Long>,
    val mustChangePassword: Boolean,
)

data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    /** In seconds, as client libraries expect. */
    val expiresIn: Long,
    val user: SessionUserDto,
)

private fun SessionUser.toDto() = SessionUserDto(id, username, name, roles, permissions, scopes, warehouses, mustChangePassword)

private fun AuthSession.toDto() = AuthSessionDto(accessToken, refreshToken, expiresInSeconds, user.toDto())

/** The session routes (docs/api/autenticacion.md). */
@RestController
@RequestMapping("/v1/auth")
class AuthController(
    private val login: Login,
    private val refreshSession: RefreshSession,
    private val logout: Logout,
    private val changePassword: ChangePassword,
) {

    @PostMapping("/login")
    @PublicRoute
    fun login(@RequestBody body: LoginRequest, origin: RequestOrigin): ResponseEntity<Any> =
        login.execute(body.username.orEmpty(), body.password.orEmpty(), origin).toResponse { it.toDto() }

    @PostMapping("/refresh")
    @PublicRoute
    fun refresh(@RequestBody body: RefreshRequest, origin: RequestOrigin): ResponseEntity<Any> =
        refreshSession.execute(body.refreshToken.orEmpty(), origin).toResponse { it.toDto() }

    /** The body is optional and no valid token is required: signing out never responds with an error. */
    @PostMapping("/logout")
    @PublicRoute
    fun logout(@RequestBody(required = false) body: RefreshRequest?): ResponseEntity<Any> {
        logout.execute(body?.refreshToken)
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    /** Rereads from the database: the permissions may have changed since the token was issued. */
    @GetMapping("/me")
    @AuthenticatedOnly
    @AllowedWithPendingPasswordChange
    fun me(actor: Actor): ResponseEntity<Any> = ResponseEntity.ok(SessionUser.of(actor).toDto())

    @PostMapping("/change-password")
    @AuthenticatedOnly
    @AllowedWithPendingPasswordChange
    fun changePassword(@RequestBody body: ChangePasswordRequest, actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        changePassword.execute(actor, body.currentPassword.orEmpty(), body.newPassword.orEmpty(), origin).toNoContent()
}
