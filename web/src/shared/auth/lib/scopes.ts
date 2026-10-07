import type { PermissionScope } from "@shared/api";

/**
 * Jerarquía de alcances. El orden es la regla: `ALL` implica `AREA`, y `AREA`
 * implica `OWN`. Se declara como arreglo (y no como comparación de cadenas)
 * porque el orden lo define el negocio, no el alfabeto.
 *
 * - `OWN`  → sus capturas, su ubicación asignada (operador de piso).
 * - `AREA` → toda su bodega (jefe de bodega).
 * - `ALL`  → todas las bodegas y reportes consolidados (gerente/admin).
 */
export const SCOPE_ORDER: readonly PermissionScope[] = ["NONE", "OWN", "AREA", "ALL"];

/** ¿`scope` alcanza para lo que exige `required`? `NONE` nunca alcanza. */
export const scopeSatisfies = (
  scope: PermissionScope,
  required: PermissionScope
): boolean => SCOPE_ORDER.indexOf(scope) >= SCOPE_ORDER.indexOf(required);

/** Alcance efectivo de una clave en el mapa que entregó `/auth/me`. */
export const scopeOf = (
  scopes: Record<string, PermissionScope> | undefined,
  permission: string
): PermissionScope => scopes?.[permission] ?? "NONE";

/** ¿La sesión tiene la clave con cualquier alcance distinto de `NONE`? */
export const hasPermission = (
  scopes: Record<string, PermissionScope> | undefined,
  permission: string
): boolean => scopeOf(scopes, permission) !== "NONE";

/** ¿La sesión tiene **todas** las claves? */
export const hasAll = (
  scopes: Record<string, PermissionScope> | undefined,
  permissions: readonly string[]
): boolean => permissions.every((permission) => hasPermission(scopes, permission));

/** ¿La sesión tiene **alguna** de las claves? */
export const hasAny = (
  scopes: Record<string, PermissionScope> | undefined,
  permissions: readonly string[]
): boolean => permissions.some((permission) => hasPermission(scopes, permission));

/**
 * Traduce el `code` de autorización del contrato (§1.3) a la acción que debe
 * tomar el usuario. Colapsar los cinco casos en «no autorizado» es el defecto
 * del legacy: el usuario no sabe si pedir un permiso, esperar o llamar a otra
 * persona.
 */
export const authorizationMessageKey = (code: string): string => {
  switch (code) {
    case "UNAUTHENTICATED":
      return "errors.UNAUTHENTICATED";
    case "PERMISSION_DENIED":
      return "errors.PERMISSION_DENIED";
    case "SCOPE_INSUFFICIENT":
      return "errors.SCOPE_INSUFFICIENT";
    case "POLICY_DENIED":
      return "errors.POLICY_DENIED";
    case "SEGREGATION_OF_DUTIES":
      return "errors.SEGREGATION_OF_DUTIES";
    case "TOKEN_REUSED":
      return "errors.TOKEN_REUSED";
    default:
      return "errors.UNKNOWN";
  }
};
