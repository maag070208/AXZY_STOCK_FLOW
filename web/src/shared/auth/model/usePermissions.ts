import { useSession } from "@shared/auth/model/useSession";
import { hasAny, hasPermission, scopeOf, scopeSatisfies } from "@shared/auth/lib/scopes";
import type { PermissionMap, PermissionScope } from "@shared/api";

/**
 * Hooks de permisos.
 *
 * Viven junto al store de sesión —su fuente de datos— y no en `entities/access`
 * por una razón de arquitectura concreta: `shared/ui` (el marcador de pantallas,
 * por ejemplo) necesita mostrar el alcance de un permiso, y `shared` **no puede
 * importar de `entities`**. La entidad `access` los reexporta para que la UI los
 * consuma con el nombre de su dominio.
 */

const NO_PERMISSIONS: PermissionMap = {};

/** Mapa completo de permisos de la sesión (para decidir sobre varios a la vez). */
export const usePermissions = (): PermissionMap => useSession().user?.scopes ?? NO_PERMISSIONS;

/** Rol principal de la sesión. Sólo para textos y acomodo: los accesos los deciden los permisos. */
export const useCurrentRole = (): string | null => useSession().user?.roles[0] ?? null;

/** Alcance efectivo del permiso en la sesión (`NONE` si no aplica). */
export const usePermissionScope = (permission: string): PermissionScope =>
  scopeOf(usePermissions(), permission);

/** ¿La sesión tiene el permiso con cualquier alcance distinto de `NONE`? */
export const useCan = (permission: string): boolean => {
  const permissions = usePermissions();
  return permissions[permission] !== undefined && hasPermission(permissions, permission);
};

/** ¿La sesión tiene el permiso con al menos el alcance pedido? */
export const useCanWithScope = (
  permission: string,
  required: PermissionScope
): boolean => scopeSatisfies(usePermissionScope(permission), required);

/** ¿La sesión tiene **alguna** de las claves? Útil para botones que admiten dos caminos. */
export const useCanAny = (permissions: readonly string[]): boolean =>
  hasAny(usePermissions(), permissions);
