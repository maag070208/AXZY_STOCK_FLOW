/**
 * Superficie pública de `shared/auth`.
 *
 * Aquí vive todo lo que la web necesita saber de la sesión: tokens, permisos,
 * alcances y el catálogo de pantallas del que se deriva el menú. Las capas
 * superiores importan de aquí (`@shared/auth`), no de los archivos internos.
 */
export { authApi } from "./api/authApi";
export {
  sessionStore,
  type SessionState,
  type SessionStatus,
} from "./model/sessionStore";
export { useSession } from "./model/useSession";
export { useSessionBootstrap } from "./model/useSessionBootstrap";
export type { AuthUser, PermissionMap } from "@shared/api";
export {
  PERMISSIONS,
  permissionKeys,
  permissionsByModule,
  permissionsGrouped,
  type Permission,
  type PermissionKey,
  type PermissionModule,
  type PermissionSpec,
} from "./model/permissions";
export {
  APP_SCREENS,
  isScreenVisible,
  referencedPermissions,
  screenLeaves,
  screenRequirementKeys,
  screenScope,
  type AppScreen,
  type ScreenLeaf,
  type ScreenRequirement,
} from "./model/screens";
export {
  useCan,
  useCanAny,
  useCanWithScope,
  useCurrentRole,
  usePermissionScope,
  usePermissions,
} from "./model/usePermissions";
export {
  authorizationMessageKey,
  hasAll,
  hasAny,
  hasPermission,
  scopeOf,
  scopeSatisfies,
  SCOPE_ORDER,
} from "./lib/scopes";
export {
  clearStoredSession,
  readSession,
  writeSession,
  type PersistedSession,
} from "./lib/tokenStorage";
