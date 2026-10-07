/** Entidad `session`: sesión activa, tokens y arranque contra `/auth/me`. */
export { authApi } from "@shared/auth/api/authApi";
export {
  sessionStore,
  type SessionState,
  type SessionStatus,
} from "@shared/auth/model/sessionStore";
export { useSession } from "@shared/auth/model/useSession";
export { useSessionBootstrap } from "@shared/auth/model/useSessionBootstrap";
export type { AuthUser, AuthTokensResponse, LoginRequest } from "@shared/api";
