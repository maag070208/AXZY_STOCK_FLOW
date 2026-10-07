import { authApi } from "@shared/auth/api/authApi";
import type { AuthTokensResponse, AuthUser } from "@shared/api";
import {
  clearStoredSession,
  readSession,
  writeSession,
  type PersistedSession,
} from "@shared/auth/lib/tokenStorage";

/**
 * Estado de sesión de la web.
 *
 * Es un **store externo** (patrón `useSyncExternalStore`) y no un slice de Redux
 * por un motivo concreto: el cliente HTTP necesita leer el token fuera del árbol
 * de React, y el token tiene que estar disponible **antes** del primer render
 * (una recarga en `/invoices` no puede pintar la pantalla de login mientras
 * llega `/auth/me`). Además, `entities` no puede importar el store de `app`, así
 * que un slice de `app` sería inalcanzable desde los hooks de permisos.
 *
 * Qué guarda y qué no:
 * - Los **tokens** y el `user` con sus permisos: esto es lo que el cliente HTTP
 *   y el menú consultan en cada petición y en cada render.
 * - El **estado de carga de `/auth/me`** (`status`), que es lo que impide
 *   redirigir a login "en falso" mientras la verificación está en vuelo.
 * - **No** guarda el `refreshToken` en memoria más de lo necesario: también se
 *   lee de `localStorage`, que es su fuente de verdad entre recargas.
 */
export type SessionStatus = "anonymous" | "loading" | "authenticated" | "error";

export interface SessionState {
  accessToken: string | null;
  refreshToken: string | null;
  user: AuthUser | null;
  status: SessionStatus;
  /** `code` del contrato del último fallo de sesión, para que la UI lo explique. */
  lastErrorCode: string | null;
  /** `details.until` de `ACCOUNT_LOCKED`. */
  lockedUntil: string | null;
}

type Listener = () => void;

/**
 * Marca de arranque en vuelo.
 *
 * Vive fuera del estado porque **no** debe provocar renders: existe sólo para
 * que un segundo montaje (React 19 en `StrictMode` monta dos veces en
 * desarrollo) no dispare un `/auth/me` duplicado.
 */
let bootstrapInFlight = false;

const buildInitialState = (): SessionState => {
  const stored: PersistedSession = readSession();
  return {
    accessToken: stored.accessToken || null,
    refreshToken: stored.refreshToken || null,
    user: null,
    // Con token guardado se entra en `loading`: hay que confirmar con `/auth/me`
    // antes de decidir si la sesión sigue siendo válida.
    status: stored.accessToken ? "loading" : "anonymous",
    lastErrorCode: null,
    lockedUntil: null,
  };
};

let state: SessionState = buildInitialState();
const listeners = new Set<Listener>();

const emit = (): void => {
  for (const listener of listeners) listener();
};

const setState = (patch: Partial<SessionState>): void => {
  state = { ...state, ...patch };
  emit();
};

export const sessionStore = {
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  /** Instantánea estable: `useSyncExternalStore` exige la misma referencia si nada cambió. */
  getState(): SessionState {
    return state;
  },

  getAccessToken(): string | null {
    return state.accessToken;
  },

  getRefreshToken(): string | null {
    return state.refreshToken;
  },

  /** ¿Hay un arranque de sesión en curso? (evita el `/auth/me` duplicado) */
  isBootstrapping(): boolean {
    return bootstrapInFlight;
  },

  markBootstrapping(value: boolean): void {
    bootstrapInFlight = value;
  },

  /* ------------------------------------------------------------- acciones */

  /** Sesión iniciada: tokens nuevos + usuario con sus permisos y alcances. */
  start(data: AuthTokensResponse): void {
    const stored = writeSession({
      accessToken: data.accessToken,
      refreshToken: data.refreshToken,
      userId: data.user.id,
    });
    setState({
      accessToken: stored.accessToken,
      refreshToken: stored.refreshToken,
      user: data.user,
      status: "authenticated",
      lastErrorCode: null,
      lockedUntil: null,
    });
  },

  /**
   * Tokens renovados por el cliente HTTP (rotación del refresh). El `user` se
   * deja como está: los permisos no cambian al renovar el token.
   */
  setTokens(tokens: { accessToken: string; refreshToken?: string }): void {
    const stored = writeSession({
      accessToken: tokens.accessToken,
      ...(tokens.refreshToken ? { refreshToken: tokens.refreshToken } : {}),
    });
    setState({ accessToken: stored.accessToken, refreshToken: stored.refreshToken });
  },

  setUser(user: AuthUser): void {
    setState({ user, status: "authenticated", lastErrorCode: null });
  },

  setLoading(): void {
    setState({ status: "loading" });
  },

  setError(code: string, details?: Record<string, unknown> | null): void {
    setState({
      status: state.accessToken ? "error" : "anonymous",
      lastErrorCode: code,
      lockedUntil: typeof details?.until === "string" ? details.until : null,
    });
  },

  /**
   * Cierre de sesión local. `notifyServer` es `false` cuando el servidor ya
   * revocó la familia (`TOKEN_REUSED`): volver a llamar a `/auth/logout` con un
   * token revocado produciría un 401 inútil.
   */
  async logout(options?: { notifyServer?: boolean }): Promise<void> {
    const refreshToken = state.refreshToken;
    if (options?.notifyServer !== false && refreshToken) {
      try {
        await authApi.logout(refreshToken);
      } catch {
        // Cerrar sesión es local y siempre debe funcionar, aunque la red falle.
      }
    }
    clearStoredSession();
    state = {
      accessToken: null,
      refreshToken: null,
      user: null,
      status: "anonymous",
      lastErrorCode: null,
      lockedUntil: null,
    };
    emit();
  },
};
