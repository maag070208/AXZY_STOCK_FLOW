package dev.axzy.stock.network.dto.auth

import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AUTENTICACIÓN — DTOs de `/v1/auth` y sus acciones
 *
 * Contrato: docs/historico/CONTRATO_API.md §2.
 *
 * Los tokens viajan en el cuerpo del login y del refresh, y **nunca se
 * registran en un log**: [dev.axzy.stock.network.error.ApiError] y los mappers
 * evitan copiarlos. El módulo no guarda nada: quien persiste los tokens es
 * `stock-datastore`, y solo en su almacén seguro.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Cuerpo de `POST /v1/auth/login`. */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

/**
 * Cuerpo de `POST /v1/auth/refresh`.
 *
 * El refresh token es **opaco**: no lleva información dentro. Es lo que permite
 * revocarlo borrando una fila, cosa imposible con un JWT de refresco.
 */
@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

/** Cuerpo de `POST /v1/auth/change-password`. Responde `204` e invalida TODAS las sesiones. */
@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/**
 * La respuesta del login y del refresh: **es la misma forma en los dos**.
 *
 * En el refresh los tokens son **nuevos** (rotación): el refresh token que se
 * presentó deja de servir. Eso es lo que hace detectable el robo de un token
 * (§2, `TOKEN_REUSED`).
 */
@Serializable
data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    /**
     * Vida del access token **en segundos**, que es lo que esperan las
     * bibliotecas de cliente. El contrato es explícito en la unidad; confundirla
     * con milisegundos produce un refresco 1000 veces más frecuente.
     */
    val expiresIn: Long,
    val user: UserDto,
)

/**
 * El usuario con su información de acceso.
 *
 * `scopes` no es decorativo: un permiso no es solo sí/no. `inventory.view` con
 * alcance `AREA` es «solo mi bodega»; con `ALL`, todo el almacén. Colapsarlo a
 * un booleano convertiría el modelo de permisos en un agujero.
 */
@Serializable
data class UserDto(
    val id: Long,
    val username: String,
    val name: String,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    /** `permissionKey` → `NONE` | `OWN` | `AREA` | `ALL`. */
    val scopes: Map<String, String> = emptyMap(),
    /** Bodegas del alcance `AREA`. Son del usuario, no del rol. */
    val warehouses: List<Long> = emptyList(),
    val mustChangePassword: Boolean = false,
)

/** Cuerpo de `POST /v1/auth/logout`: revoca el refresh token presentado **y su familia**. */
@Serializable
data class LogoutRequest(
    val refreshToken: String,
)
