import { hasAll, hasAny, scopeOf } from "@shared/auth/lib/scopes";
import type { Permission } from "@shared/auth/model/permissions";
import type { PermissionMap, PermissionScope } from "@shared/api";

/**
 * Catálogo de pantallas de la web: **la fuente única del menú lateral**.
 *
 * El legacy tenía el menú hardcodeado en `Layout.tsx` **y duplicado** en
 * `Home.tsx`, con `NavItem` usando `<a href>` (recarga completa y pérdida de
 * sesión). Aquí el menú se **deriva de los permisos de la sesión**: agregar una
 * pantalla es agregar una entrada en este arreglo, y ni el layout ni el
 * `Home` necesitan enterarse.
 *
 * Reglas de visibilidad:
 * - `requirement`: `anyOf` (basta una clave) o `allOf` (hacen falta todas).
 * - `fixedRole`: regla fija por rol, sin mirar permisos (excepcional; existe
 *   para pantallas como «conteo asignado a mí»).
 * - Un grupo con `children` es visible si lo es al menos uno de sus hijos.
 * - Sin `requirement` ni `fixedRole`, la pantalla la ve cualquier sesión.
 *
 * El backend revalida **siempre**: ocultar el enlace no es autorizar.
 */

export type ScreenRequirement =
  | { readonly anyOf: readonly Permission[] }
  | { readonly allOf: readonly Permission[] };

export interface AppScreen {
  readonly id: string;
  /** Clave i18n en el namespace `common` (p. ej. `nav.invoices`). */
  readonly labelKey: string;
  /** Ruta del contrato de UI a la que navega el ítem. */
  readonly path?: string;
  /** Modo de resaltado. Por defecto `prefix`. */
  readonly match?: "exact" | "prefix";
  /** Rutas hijas que **no** deben resaltar este ítem. */
  readonly excludes?: readonly string[];
  readonly requirement?: ScreenRequirement;
  readonly fixedRole?: string;
  /** Permisos de los botones de la pantalla (para la matriz «qué ve cada rol»). */
  readonly actions?: readonly Permission[];
  readonly children?: readonly AppScreen[];
}

/** Menú lateral completo, en el orden en que se muestra. */
export const APP_SCREENS: readonly AppScreen[] = [
  { id: "home", labelKey: "nav.home", path: "/", match: "exact" },

  {
    id: "reception",
    labelKey: "nav.reception",
    children: [
      {
        id: "invoices",
        labelKey: "nav.invoices",
        path: "/invoices",
        requirement: { anyOf: ["invoice.view"] },
        actions: ["invoice.create", "invoice.edit", "invoice.delete"],
      },
      {
        id: "invoiceDetails",
        labelKey: "nav.invoiceDetails",
        path: "/invoices/:id",
        requirement: { anyOf: ["invoice.view"] },
        actions: ["invoice.capture", "invoice.locate", "invoice.approve", "invoice.close"],
      },
      {
        id: "pendingApproval",
        labelKey: "nav.pendingApproval",
        path: "/invoices/pending-approval",
        requirement: { anyOf: ["invoice.view"] },
        actions: ["invoice.approve", "invoice.reject"],
      },
    ],
  },

  {
    id: "fulfillment",
    labelKey: "nav.fulfillment",
    children: [
      {
        id: "orders",
        labelKey: "nav.orders",
        path: "/orders",
        requirement: { anyOf: ["order.view"] },
        actions: ["order.create", "order.edit", "order.delete"],
      },
      {
        id: "orderDetails",
        labelKey: "nav.orderDetails",
        path: "/orders/:id",
        requirement: { anyOf: ["order.view"] },
        actions: ["order.package", "order.confirm", "order.convert", "order.assign"],
      },
      {
        id: "transfers",
        labelKey: "nav.transfers",
        path: "/transfers",
        requirement: { anyOf: ["transfer.view"] },
        actions: ["transfer.create", "transfer.dispatch", "transfer.receive", "transfer.cancel"],
      },
    ],
  },

  {
    id: "warehouse",
    labelKey: "nav.warehouse",
    children: [
      {
        id: "products",
        labelKey: "nav.products",
        path: "/products",
        requirement: { anyOf: ["product.view"] },
        actions: ["product.create", "product.edit", "product.delete", "product.import"],
      },
      {
        id: "locations",
        labelKey: "nav.locations",
        path: "/locations",
        requirement: { anyOf: ["location.view"] },
        actions: ["location.create", "location.edit", "location.delete"],
      },
      {
        id: "stock",
        labelKey: "nav.stock",
        path: "/inventory",
        requirement: { anyOf: ["inventory.view"] },
        actions: ["inventory.move", "inventory.adjust_increase", "inventory.count"],
      },
      {
        id: "kardex",
        labelKey: "nav.kardex",
        path: "/kardex",
        requirement: { anyOf: ["kardex.view"] },
        actions: ["kardex.export"],
      },
      {
        id: "adjustmentApproval",
        labelKey: "nav.adjustmentApproval",
        path: "/inventory/pending-approval",
        requirement: { anyOf: ["inventory.approve_adjustment"] },
      },
    ],
  },

  {
    id: "labels",
    labelKey: "nav.labels",
    path: "/labels",
    requirement: { anyOf: ["label.print", "label.queue_view"] },
    actions: ["label.print", "label.reprint", "label.template_edit"],
  },

  {
    id: "reports",
    labelKey: "nav.reports",
    path: "/reports",
    requirement: { anyOf: ["report.view"] },
    actions: ["report.export", "report.financial", "report.audit"],
  },

  {
    id: "settings",
    labelKey: "nav.settings",
    children: [
      {
        id: "users",
        labelKey: "nav.users",
        path: "/users",
        requirement: { anyOf: ["user.view"] },
        actions: ["user.create", "user.edit", "user.delete", "user.reset_password"],
      },
      {
        id: "audit",
        labelKey: "nav.audit",
        path: "/audit",
        requirement: { anyOf: ["audit.view"] },
      },
      {
        id: "health",
        labelKey: "nav.health",
        path: "/health",
        requirement: { anyOf: ["system.health"] },
      },
    ],
  },
];

const requirementKeys = (screen: AppScreen): readonly Permission[] => {
  if (!screen.requirement) return [];
  return "allOf" in screen.requirement ? screen.requirement.allOf : screen.requirement.anyOf;
};

/** Claves de permiso que exige ver la pantalla (vacío = cualquier sesión). */
export const screenRequirementKeys = (screen: AppScreen): readonly Permission[] =>
  requirementKeys(screen);

/** ¿La pantalla es visible para estos permisos y este rol? */
export const isScreenVisible = (
  permissions: PermissionMap | undefined,
  screen: AppScreen,
  role?: string | null
): boolean => {
  if (screen.fixedRole) return role === screen.fixedRole;
  if (screen.requirement) {
    return "allOf" in screen.requirement
      ? hasAll(permissions, screen.requirement.allOf)
      : hasAny(permissions, screen.requirement.anyOf);
  }
  if (screen.children?.length) {
    return screen.children.some((child) => isScreenVisible(permissions, child, role));
  }
  return true;
};

/** Alcance efectivo de un permiso en la sesión (para pintar «sólo tu bodega», etc.). */
export const screenScope = (
  permissions: PermissionMap | undefined,
  permission: Permission
): PermissionScope => scopeOf(permissions, permission);

/** Todas las hojas del catálogo, con su grupo, en orden de menú. */
export interface ScreenLeaf {
  readonly screen: AppScreen;
  readonly group?: AppScreen;
}

export const screenLeaves = (): ScreenLeaf[] => {
  const leaves: ScreenLeaf[] = [];
  const walk = (screens: readonly AppScreen[], group?: AppScreen): void => {
    for (const screen of screens) {
      if (screen.children?.length) walk(screen.children, group ?? screen);
      else leaves.push({ screen, ...(group ? { group } : {}) });
    }
  };
  walk(APP_SCREENS);
  return leaves;
};

/** Claves de permiso mencionadas por cualquier pantalla del catálogo. */
export const referencedPermissions = (): Set<string> => {
  const keys = new Set<string>();
  const walk = (screens: readonly AppScreen[]): void => {
    for (const screen of screens) {
      for (const key of requirementKeys(screen)) keys.add(key);
      if (screen.children) walk(screen.children);
    }
  };
  walk(APP_SCREENS);
  return keys;
};
