/**
 * Entidad `identity`: usuarios, roles y el catálogo de permisos.
 *
 * **Es de LECTURA.** Las rutas de escritura no existen todavía; ver el comentario
 * de `api/identityApi.ts` — está ahí precisamente para que nadie dé por hecho lo
 * contrario.
 */
export { fetchPermissionCatalog, fetchRoles, fetchUsers } from "./api/identityApi";
export type {
  PermissionAdmin,
  PermissionCatalog,
  PermissionModuleAdmin,
  RoleAdmin,
  RoleListParams,
  UserAdmin,
  UserListParams,
} from "@shared/api";
