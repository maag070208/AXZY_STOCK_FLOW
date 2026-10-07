import type { PermissionMap, PermissionScope } from "@shared/api";
import {
  hasAny,
  hasPermission,
  scopeOf,
  scopeSatisfies,
} from "@shared/auth/lib/scopes";
import { useSession } from "@shared/auth/model/useSession";

/**
 * Hooks de permisos de la sesión, con el nombre del dominio `access`.
 *
 * La implementación vive en `shared/auth` porque `shared/ui` también la necesita
 * (el marcador de pantallas muestra el alcance de un permiso) y `shared` no
 * puede depender de `entities`. Aquí se reexporta para que las features, los
 * widgets y las páginas la consuman desde su capa natural.
 */

const NO_PERMISSIONS: PermissionMap = {};

/** Mapa completo de permisos de la sesión (para decidir sobre varios a la vez). */
export const usePermissions = (): PermissionMap => useSession().user?.scopes ?? NO_PERMISSIONS;

/** Rol principal de la sesión. Sólo para textos y acomodo: los accesos los deciden los permisos. */
export const useCurrentRole = (): string | null => useSession().user?.roles[0] ?? null;

/** Alcance efectivo del permiso en la sesión (`NONE` si no aplica). */
export const usePermissionScope = (permission: string): PermissionScope =>
  scopeOf(usePermissions(), permission);

/** ¿La sesión tiene el permiso con cualquier alcance? */
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
