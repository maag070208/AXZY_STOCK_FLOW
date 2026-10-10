import { get, post } from "@shared/api/client";
import type { AuthSession, SessionUser } from "./model";

/**
 * Las rutas de sesión. **Sin `/v1`**: el prefijo ya está en la URL base
 * (docs/arquitectura/web-fsd.md §2.3).
 */
export const authApi = {
  login: (username: string, password: string): Promise<AuthSession> =>
    post<AuthSession>("/auth/login", { username, password }),

  /** El refresh lo usa el cliente HTTP por su cuenta; esto es para las pruebas. */
  refresh: (refreshToken: string): Promise<AuthSession> =>
    post<AuthSession>("/auth/refresh", { refreshToken }),

  logout: (refreshToken: string | null): Promise<void> =>
    post<void>("/auth/logout", { refreshToken }),

  /** Relee los permisos de la base: pueden haber cambiado desde que se emitió el token. */
  me: (): Promise<SessionUser> => get<SessionUser>("/auth/me"),

  changePassword: (currentPassword: string, newPassword: string): Promise<void> =>
    post<void>("/auth/change-password", { currentPassword, newPassword }),
};
