/**
 * The session, as the API describes it (docs/api/autenticacion.md).
 *
 * `permissions` are the **granted** permissions (those with a different scope
 * of `NONE`) and `scopes` the effective scope of each one. The web uses them to
 * draw the menu and to avoid offering what will fail; **the one who decides remains
 * being the API** (docs/arquitectura/api-spring.md §6).
 */
export interface SessionUser {
  id: number;
  username: string;
  name: string;
  roles: string[];
  permissions: string[];
  scopes: Record<string, string>;
  warehouses: number[];
  /** If `true`, the only possible screen is to change the password (I8). */
  mustChangePassword: boolean;
}

export interface AuthSession {
  accessToken: string;
  /** In seconds, as any client expects. */
  expiresIn: number;
  refreshToken: string;
  user: SessionUser;
}

export interface AuthState {
  token: string | null;
  refreshToken: string | null;
  user: SessionUser | null;
  status: "idle" | "loading" | "error";
  error: string | null;
}
