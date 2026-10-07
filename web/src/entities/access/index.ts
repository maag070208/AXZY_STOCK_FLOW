/**
 * Entidad `access`: lo que la UI necesita para decidir **qué puede hacer la
 * sesión** y para mostrar la bitácora. La autoridad real es el backend; esto es
 * la capa de presentación.
 *
 * Las funciones puras de alcance (`scopeSatisfies`, …) viven en `shared/auth`
 * porque también las usa el catálogo de pantallas; aquí sólo se exponen los
 * hooks, que es lo que consume la UI.
 */
export { fetchAuditLog } from "./api/auditApi";
export {
  useCan,
  useCanAny,
  useCanWithScope,
  useCurrentRole,
  usePermissionScope,
  usePermissions,
} from "./model/usePermission";
export type { AuditLogEntry, AuditLogParams, PermissionMap, PermissionScope } from "@shared/api";
