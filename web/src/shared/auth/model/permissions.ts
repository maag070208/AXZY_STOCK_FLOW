import type { PermissionScope } from "@shared/api";

/**
 * Catálogo de permisos del dominio de almacén.
 *
 * Fuente: `PLAN_ROLES_PERMISOS_POLITICAS.md` §2 (ocho módulos, 82 permisos).
 * **El catálogo real lo sirve la base de datos** y viaja en
 * `user.permissions`/`user.scopes`; esta lista existe para dos cosas concretas:
 *
 * 1. Tipar las claves (una errata en `useCan("invoice.aprobe")` no compila).
 * 2. Poder dibujar la matriz de la consola de roles cuando la web la implemente.
 *
 * Nada de autorización se decide aquí: una clave que no esté en la lista pero
 * llegue en la sesión **sigue funcionando**, porque el `scopes` del servidor es
 * un `Record<string, PermissionScope>`.
 */

/**
 * Clave de permiso.
 *
 * Se declara como `string` y no como la unión de las claves del catálogo porque
 * el catálogo real lo sirve la base de datos: una clave nueva debe funcionar sin
 * recompilar la web. `PermissionKey` (abajo) da el tipado fuerte **sólo** para
 * las claves que la UI menciona en código.
 */
export type Permission = string;

/** Alcances que cada permiso admite, según el catálogo del plan. */
export interface PermissionSpec {
  readonly key: Permission;
  readonly module: PermissionModule;
  readonly scopes: readonly PermissionScope[];
  /** Los permisos sensibles se auditan **al usarse**, no sólo al denegarse. */
  readonly sensitive?: boolean;
  /** Los de servicio se autentican con API key, no con sesión de usuario. */
  readonly service?: true;
}

export type PermissionModule =
  | "identidad"
  | "catalogo"
  | "almacen"
  | "recepcion"
  | "surtido"
  | "inventario"
  | "transferencias"
  | "etiquetado"
  | "reportes"
  | "sistema";

/** Todos los alcances: se usa para no repetir el arreglo en cada permiso. */
const ALL_SCOPES: readonly PermissionScope[] = ["OWN", "AREA", "ALL"];
const AREA_ALL: readonly PermissionScope[] = ["AREA", "ALL"];
const ALL_ONLY: readonly PermissionScope[] = ["ALL"];

export const PERMISSIONS = [
  /* ------------------------------------------------------------ identidad */
  { key: "user.view", module: "identidad", scopes: ALL_SCOPES },
  { key: "user.create", module: "identidad", scopes: ALL_ONLY },
  { key: "user.edit", module: "identidad", scopes: ALL_ONLY },
  { key: "user.delete", module: "identidad", scopes: ALL_ONLY, sensitive: true },
  { key: "user.reset_password", module: "identidad", scopes: ALL_ONLY, sensitive: true },
  { key: "role.manage", module: "identidad", scopes: ALL_ONLY, sensitive: true },
  { key: "role.assign", module: "identidad", scopes: ALL_ONLY, sensitive: true },
  { key: "permission.exception", module: "identidad", scopes: ALL_ONLY, sensitive: true },
  { key: "audit.view", module: "identidad", scopes: ALL_SCOPES },
  { key: "session.revoke", module: "identidad", scopes: ALL_ONLY, sensitive: true },

  /* -------------------------------------------------------------- catálogo */
  { key: "product.view", module: "catalogo", scopes: ALL_ONLY },
  { key: "product.create", module: "catalogo", scopes: ALL_ONLY },
  { key: "product.edit", module: "catalogo", scopes: ALL_ONLY },
  { key: "product.delete", module: "catalogo", scopes: ALL_ONLY, sensitive: true },
  { key: "product.import", module: "catalogo", scopes: ALL_ONLY, sensitive: true },
  { key: "product.sync_barcode", module: "catalogo", scopes: ALL_ONLY, sensitive: true },

  /* ------------------------------------------------------- almacén físico */
  { key: "warehouse.view", module: "almacen", scopes: ALL_ONLY },
  { key: "warehouse.create", module: "almacen", scopes: ALL_ONLY },
  { key: "warehouse.edit", module: "almacen", scopes: ALL_ONLY },
  { key: "location.view", module: "almacen", scopes: ALL_SCOPES },
  { key: "location.create", module: "almacen", scopes: AREA_ALL },
  { key: "location.edit", module: "almacen", scopes: AREA_ALL },
  { key: "location.delete", module: "almacen", scopes: ALL_ONLY, sensitive: true },
  { key: "location.print_labels", module: "almacen", scopes: AREA_ALL },

  /* ------------------------------------------------------------- recepción */
  { key: "invoice.view", module: "recepcion", scopes: ALL_SCOPES },
  { key: "invoice.create", module: "recepcion", scopes: AREA_ALL },
  { key: "invoice.edit", module: "recepcion", scopes: ALL_SCOPES },
  { key: "invoice.delete", module: "recepcion", scopes: ALL_ONLY, sensitive: true },
  { key: "invoice.capture", module: "recepcion", scopes: ALL_SCOPES },
  { key: "invoice.assign", module: "recepcion", scopes: AREA_ALL },
  { key: "invoice.locate", module: "recepcion", scopes: ALL_SCOPES },
  { key: "invoice.approve", module: "recepcion", scopes: AREA_ALL, sensitive: true },
  { key: "invoice.reject", module: "recepcion", scopes: AREA_ALL },
  { key: "invoice.close", module: "recepcion", scopes: AREA_ALL },
  {
    key: "invoice.import_external",
    module: "recepcion",
    scopes: ALL_ONLY,
    sensitive: true,
    service: true,
  },

  /* --------------------------------------------------------------- surtido */
  { key: "order.view", module: "surtido", scopes: ALL_SCOPES },
  { key: "order.create", module: "surtido", scopes: AREA_ALL },
  { key: "order.edit", module: "surtido", scopes: ALL_SCOPES },
  { key: "order.delete", module: "surtido", scopes: ALL_ONLY, sensitive: true },
  { key: "order.assign", module: "surtido", scopes: AREA_ALL },
  { key: "order.package", module: "surtido", scopes: ALL_SCOPES },
  { key: "order.confirm", module: "surtido", scopes: AREA_ALL },
  { key: "order.convert", module: "surtido", scopes: ALL_ONLY, sensitive: true },

  /* ------------------------------------------------------------ inventario */
  { key: "inventory.view", module: "inventario", scopes: ALL_SCOPES },
  { key: "inventory.view_cost", module: "inventario", scopes: ALL_ONLY, sensitive: true },
  { key: "inventory.move", module: "inventario", scopes: ALL_SCOPES },
  { key: "inventory.adjust_increase", module: "inventario", scopes: AREA_ALL },
  { key: "inventory.adjust_shortage", module: "inventario", scopes: AREA_ALL, sensitive: true },
  { key: "inventory.adjust_damage", module: "inventario", scopes: AREA_ALL, sensitive: true },
  { key: "inventory.adjust_theft", module: "inventario", scopes: ALL_ONLY, sensitive: true },
  { key: "inventory.adjust_count_error", module: "inventario", scopes: AREA_ALL },
  {
    key: "inventory.approve_adjustment",
    module: "inventario",
    scopes: AREA_ALL,
    sensitive: true,
  },
  { key: "inventory.count", module: "inventario", scopes: ALL_SCOPES },
  { key: "inventory.reconcile", module: "inventario", scopes: ALL_ONLY, sensitive: true },
  { key: "kardex.view", module: "inventario", scopes: ALL_SCOPES },
  { key: "kardex.export", module: "inventario", scopes: AREA_ALL },

  /* -------------------------------------------------------- transferencias */
  { key: "transfer.view", module: "transferencias", scopes: ALL_SCOPES },
  { key: "transfer.create", module: "transferencias", scopes: AREA_ALL },
  { key: "transfer.dispatch", module: "transferencias", scopes: AREA_ALL },
  { key: "transfer.receive", module: "transferencias", scopes: AREA_ALL },
  { key: "transfer.cancel", module: "transferencias", scopes: ALL_ONLY, sensitive: true },
  { key: "transit.view", module: "transferencias", scopes: AREA_ALL },

  /* ------------------------------------------------------------- etiquetado */
  { key: "label.print", module: "etiquetado", scopes: ALL_SCOPES },
  { key: "label.reprint", module: "etiquetado", scopes: AREA_ALL },
  { key: "label.template_view", module: "etiquetado", scopes: AREA_ALL },
  { key: "label.template_edit", module: "etiquetado", scopes: ALL_ONLY, sensitive: true },
  { key: "label.queue_view", module: "etiquetado", scopes: AREA_ALL },
  {
    key: "label.queue_manage",
    module: "etiquetado",
    scopes: ALL_ONLY,
    sensitive: true,
    service: true,
  },

  /* --------------------------------------------------------------- reportes */
  { key: "report.view", module: "reportes", scopes: ALL_SCOPES },
  { key: "report.export", module: "reportes", scopes: AREA_ALL },
  { key: "report.financial", module: "reportes", scopes: ALL_ONLY, sensitive: true },
  { key: "report.audit", module: "reportes", scopes: ALL_ONLY, sensitive: true },

  /* --------------------------------------------------------------- sistema */
  { key: "system.config", module: "sistema", scopes: ALL_ONLY, sensitive: true },
  { key: "system.health", module: "sistema", scopes: ALL_ONLY },
  { key: "print_device.manage", module: "sistema", scopes: ALL_ONLY, sensitive: true },
  { key: "integration.manage", module: "sistema", scopes: ALL_ONLY, sensitive: true },
] as const satisfies readonly PermissionSpec[];

/** Claves conocidas del catálogo, para tipar lo que la UI menciona en código. */
export type PermissionKey = (typeof PERMISSIONS)[number]["key"];

export const permissionKeys: readonly PermissionKey[] = PERMISSIONS.map((p) => p.key);

/** Permisos de un módulo, en el orden del catálogo. */
export const permissionsByModule = (module: PermissionModule): PermissionSpec[] =>
  PERMISSIONS.filter((permission) => permission.module === module);

/** El catálogo agrupado por módulo, para la consola de roles. */
export const permissionsGrouped = (): Array<{
  module: PermissionModule;
  permissions: PermissionSpec[];
}> => {
  const modules: PermissionModule[] = [];
  for (const permission of PERMISSIONS) {
    if (!modules.includes(permission.module)) modules.push(permission.module);
  }
  return modules.map((module) => ({ module, permissions: permissionsByModule(module) }));
};
