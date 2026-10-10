/**
 * La sesión, tal como la describe la API (docs/api/autenticacion.md).
 *
 * `permissions` son los permisos **concedidos** (los que tienen alcance distinto
 * de `NONE`) y `scopes` el alcance efectivo de cada uno. La web los usa para
 * dibujar el menú y para no ofrecer lo que va a fallar; **quien decide sigue
 * siendo la API** (docs/arquitectura/api-spring.md §6).
 */
export interface SessionUser {
  id: number;
  username: string;
  name: string;
  roles: string[];
  permissions: string[];
  scopes: Record<string, string>;
  warehouses: number[];
  /** Si es `true`, la única pantalla posible es cambiar la contraseña (I8). */
  mustChangePassword: boolean;
}

export interface AuthSession {
  accessToken: string;
  /** En segundos, como lo espera cualquier cliente. */
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
