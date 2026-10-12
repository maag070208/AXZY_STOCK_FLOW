package dev.axzy.stock.mobile.core.session

import kotlinx.serialization.Serializable

// The bodies of the session routes (`docs/api/autenticacion.md`). The ones that carry a secret are plain classes,
// not data classes: a generated `toString` would write the password or the tokens wherever the object is printed.

@Serializable
internal class LoginRequest(val username: String, val password: String)

@Serializable
internal class RefreshRequest(val refreshToken: String)

@Serializable
internal class SessionDto(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto,
)

@Serializable
internal data class UserDto(
    val id: Long,
    val username: String,
    val name: String,
    val permissions: List<String> = emptyList(),
    val mustChangePassword: Boolean = false,
)

internal fun UserDto.toSessionUser() = SessionUser(
    id = id,
    username = username,
    name = name,
    permissions = permissions.toSet(),
)
