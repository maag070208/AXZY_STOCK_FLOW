import { get } from "@shared/api";
import type {
  Page,
  PermissionCatalog,
  RoleAdmin,
  RoleListParams,
  UserAdmin,
  UserListParams,
} from "@shared/api";

/**
 * API de identidad (administración de acceso).
 *
 * ── SOLO HAY LECTURA, Y LA INTERFAZ NO PUEDE FINGIR LO CONTRARIO ───────────
 *
 * La API publica **tres rutas `GET`**: usuarios, roles y permisos. **No hay
 * ninguna ruta de escritura**: no se puede crear un usuario, ni cambiarle los
 * roles, ni restablecerle la contraseña, ni editar un rol.
 *
 * La pantalla anterior de Usuarios lo daba por hecho: declaraba
 * `["GET /v1/users", "POST /v1/users", "PUT /v1/users/{id}"]` como si las tres
 * existieran. Dos de esas tres rutas **no existen**, y prometerlas en la
 * documentación de la pantalla es la misma clase de mentira que la maqueta que
 * decía que un endpoint faltaba cuando ya estaba.
 *
 * Aquí no se declaran funciones de escritura que devolverían `404`. Cuando
 * existan las rutas, se añaden — y el listado de este archivo es la deuda.
 */

export const fetchUsers = (params: UserListParams = {}): Promise<Page<UserAdmin>> =>
  get<Page<UserAdmin>>("/users", { params: { size: 100, ...params } });

export const fetchRoles = (params: RoleListParams = {}): Promise<Page<RoleAdmin>> =>
  get<Page<RoleAdmin>>("/roles", { params: { size: 200, ...params } });

/** El catálogo entero: **sin paginar**, por decisión del contrato (§1.4). */
export const fetchPermissionCatalog = (): Promise<PermissionCatalog> =>
  get<PermissionCatalog>("/permissions");
