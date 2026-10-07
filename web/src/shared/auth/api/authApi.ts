import { get, post } from "@shared/api";
import type { AuthTokensResponse, AuthUser, LoginRequest } from "@shared/api";

/**
 * Llamadas de autenticación (§2 del contrato).
 *
 * Vive en `shared/auth` y no en una entidad porque la sesión la necesitan el
 * cliente HTTP (para renovar el token) y el arranque de la app (para
 * reconstruir permisos), antes de que exista cualquier página.
 */
export const authApi = {
  /** `200` con tokens nuevos. Errores: `INVALID_CREDENTIALS`, `ACCOUNT_DISABLED`, `ACCOUNT_LOCKED`, `RATE_LIMITED`. */
  login: (credentials: LoginRequest): Promise<AuthTokensResponse> =>
    post<AuthTokensResponse>("/auth/login", credentials),

  /** `401 TOKEN_REUSED` significa reuso detectado y **toda la familia revocada**. */
  refresh: (refreshToken: string): Promise<AuthTokensResponse> =>
    post<AuthTokensResponse>("/auth/refresh", { refreshToken }),

  /** `204`. Revoca el refresh token presentado y su familia. */
  logout: async (refreshToken: string): Promise<void> => {
    await post<unknown>("/auth/logout", { refreshToken });
  },

  /** `GET /v1/auth/me`: permisos y alcances vigentes sin volver a autenticar. */
  me: (): Promise<AuthUser> => get<AuthUser>("/auth/me"),

  /**
   * `204`. **Invalida todas las sesiones**, incluida la actual: tras cambiarla
   * hay que volver a entrar.
   *
   * No lo consume todavía ninguna pantalla (el flujo de cambio de contraseña
   * llega con la bandeja de usuarios), pero se declara aquí porque el contrato
   * lo fija y el cliente HTTP ya maneja su caso: `204` sin cuerpo.
   */
  changePassword: async (currentPassword: string, newPassword: string): Promise<void> => {
    await post<unknown>("/auth/change-password", { currentPassword, newPassword });
  },
};
