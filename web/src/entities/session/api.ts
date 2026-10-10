import { get, post } from "@shared/api/client";
import type { AuthSession, SessionUser } from "./model";

/**
 * The session routes. **Without `/v1`**: the prefix is already in the base URL
 * (docs/arquitectura/web-fsd.md §2.3).
 */
export const authApi = {
  login: (username: string, password: string): Promise<AuthSession> =>
    post<AuthSession>("/auth/login", { username, password }),

  /** The refresh is used by the HTTP client on its own; this is for the tests. */
  refresh: (refreshToken: string): Promise<AuthSession> =>
    post<AuthSession>("/auth/refresh", { refreshToken }),

  logout: (refreshToken: string | null): Promise<void> =>
    post<void>("/auth/logout", { refreshToken }),

  /** Rereads the permissions from the database: they may have changed since the token was issued. */
  me: (): Promise<SessionUser> => get<SessionUser>("/auth/me"),

  changePassword: (currentPassword: string, newPassword: string): Promise<void> =>
    post<void>("/auth/change-password", { currentPassword, newPassword }),
};
