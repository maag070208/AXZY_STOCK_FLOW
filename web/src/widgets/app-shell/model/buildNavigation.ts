import type { AppScreen, PermissionMap } from "@shared/auth";
import { isScreenVisible } from "@shared/auth";
import type { ITNavigationItem, ITNavigationSubItemEntry } from "@axzydev/axzy_ui_system";

/**
 * Traducción del catálogo de pantallas (`APP_SCREENS`) a la navegación del
 * `ITSidebar`.
 *
 * **Aquí no hay ni una ruta escrita a mano**: se recorre el catálogo, se evalúa
 * cada pantalla contra los permisos de la sesión y se descarta lo que no aplica.
 * El legacy tenía el menú hardcodeado en `Layout.tsx` y duplicado en `Home.tsx`,
 * así que agregar una pantalla exigía acordarse de dos sitios.
 *
 * Detalles del kit que condicionan la forma:
 * - `ITSidebar` tiene **dos niveles**: un hijo con `children` se convierte en
 *   grupo con título, no en una tercera anidación.
 * - `isActive` no se calcula aquí porque depende de la ruta actual; se resuelve
 *   con el callback `isActive` que recibe esta función.
 */

export interface NavigationBuildContext {
  /** Etiqueta por clave i18n (`nav.invoices` → «Facturas»). */
  translate: (labelKey: string) => string;
  /** Icono por `id` de pantalla. */
  icons: Record<string, React.ReactNode>;
  /** ¿La ruta actual corresponde a esta pantalla? */
  isActive: (screen: AppScreen) => boolean;
  /** Navega a una ruta. */
  goTo: (path: string) => void;
}

const toSubItem = (
  screen: AppScreen,
  context: NavigationBuildContext
): ITNavigationSubItemEntry => ({
  id: screen.id,
  label: context.translate(screen.labelKey),
  action: () => {
    if (screen.path) context.goTo(screen.path);
  },
  isActive: context.isActive(screen),
});

export const buildNavigationItems = (
  screens: readonly AppScreen[],
  permissions: PermissionMap | undefined,
  role: string | null,
  context: NavigationBuildContext
): ITNavigationItem[] => {
  const items: ITNavigationItem[] = [];

  for (const screen of screens) {
    if (!isScreenVisible(permissions, screen, role)) continue;

    const visibleChildren = (screen.children ?? []).filter((child) =>
      isScreenVisible(permissions, child, role)
    );

    const item: ITNavigationItem = {
      id: screen.id,
      label: context.translate(screen.labelKey),
      isActive: context.isActive(screen),
      ...(context.icons[screen.id] ? { icon: context.icons[screen.id] } : {}),
      ...(screen.path ? { action: () => context.goTo(screen.path as string) } : {}),
      ...(visibleChildren.length > 0
        ? {
            subitems: visibleChildren.map((child) =>
              child.children?.length
                ? {
                    id: child.id,
                    label: context.translate(child.labelKey),
                    items: child.children
                      .filter((leaf) => isScreenVisible(permissions, leaf, role))
                      .map((leaf) => toSubItem(leaf, context)),
                  }
                : toSubItem(child, context)
            ),
          }
        : {}),
    };

    items.push(item);
  }

  return items;
};

/**
 * ¿La ruta actual corresponde a la pantalla? `prefix` por defecto para que
 * `/invoices/15` resalte «Facturas», y `excludes` para que las pantallas
 * hermanas (`/invoices/pending-approval`) no resalten ambas.
 */
export const matchesScreenPath = (
  pathname: string,
  screen: AppScreen
): boolean => {
  if (!screen.path) {
    return (screen.children ?? []).some((child) => matchesScreenPath(pathname, child));
  }

  const exact = screen.match === "exact";
  const hit = exact
    ? pathname === screen.path
    : pathname === screen.path || pathname.startsWith(`${screen.path}/`);
  if (!hit) return false;

  return !(screen.excludes ?? []).some(
    (excluded) => pathname === excluded || pathname.startsWith(`${excluded}/`)
  );
};
