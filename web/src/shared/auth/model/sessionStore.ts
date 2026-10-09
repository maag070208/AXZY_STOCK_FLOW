import { authApi } from "@shared/auth/api/authApi";
import { setSessionHooks } from "@shared/api";
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

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL PUENTE CON EL CLIENTE HTTP — y por qué esta llamada faltaba
 *
 * `shared/api/session.ts` declara los ganchos que el cliente HTTP usa para leer
 * el token, pero **son variables con valores por defecto vacíos**:
 *
 *     let getToken: () => string | null = () => null;   // ← sin conectar
 *
 * Alguien tiene que rellenarlas, y **nadie lo hacía**: `setSessionHooks` estaba
 * definido y exportado, y no se invocaba en ningún punto del proyecto. El
 * resultado era que `getSessionToken()` devolvía `null` **siempre**, así que
 * ninguna petición llevaba la cabecera `Authorization`.
 *
 * ── EL SÍNTOMA, QUE NO SE PARECÍA A LA CAUSA ──────────────────────────────
 *
 * Entrar funcionaba (el login no necesita token) y navegar por dentro de la
 * aplicación también, porque las pantallas ya tenían los datos. **Al recargar,
 * la sesión se perdía**: `useSessionBootstrap` veía el token guardado y pedía
 * `/auth/me`, esa petición salía **sin** cabecera, el servidor respondía `401`,
 * y el cliente HTTP interpretaba el `401` como «la sesión ya no vale» y la
 * cerraba.
 *
 * Es **exactamente lo contrario** de lo que se decidió al elegir `localStorage`
 * en `tokenStorage.ts`: «el turno de noche recarga la web a media operación y
 * perder la sesión obliga a volver a teclear credenciales en una terminal de
 * piso». La decisión estaba escrita y el mecanismo no estaba conectado.
 *
 * Lo encontró el e2e de navegador, y **ningún otro test podía encontrarlo**:
 *
 *   · `tsc` compila: los ganchos tienen valores por defecto válidos.
 *   · `eslint` no ve un import sin usar, porque `setSessionHooks` no se
 *     importaba en ningún sitio: su ausencia no es un error de lint.
 *   · Los tests de API no tocan el navegador ni `localStorage`.
 *
 * Se conecta **aquí** y no en `main.tsx` para que sea imposible montar el store
 * sin el puente: el estado de sesión y su cableado con el HTTP viven juntos.
 * ═══════════════════════════════════════════════════════════════════════════
 */
setSessionHooks({
  tokenGetter: () => sessionStore.getAccessToken(),
  refreshTokenGetter: () => sessionStore.getRefreshToken(),
  tokensSetter: (tokens) => sessionStore.setTokens(tokens),
  unauthorizedHandler: () => {
    // El `401` lo resolvió el cliente HTTP; aquí solo se refleja el cierre para
    // que la interfaz deje de creer que hay sesión.
    void sessionStore.logout({ notifyServer: false });
  },
  tokensRevokedHandler: (code) => sessionStore.setError(code),
});
