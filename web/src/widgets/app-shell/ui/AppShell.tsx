import {
  APP_SCREENS,
  sessionStore,
  useSession,
  type AppScreen,
  type AuthUser,
  type PermissionMap,
} from "@shared/auth";
import { ITLayout, type ITNavigationItem, type ITSidebarProps } from "@axzydev/axzy_ui_system";
import type { ITLayoutProps } from "@axzydev/axzy_ui_system";
import { useCallback } from "react";
import { FaGaugeHigh, FaGear, FaTruckFast, FaWarehouse } from "react-icons/fa6";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router-dom";
import { buildNavigationItems, matchesScreenPath } from "../model/buildNavigation";

/**
 * Iconos del menú, por `id` de pantalla del catálogo.
 *
 * Sólo los grupos de primer nivel llevan icono: el sidebar se muestra plegado
 * (sólo iconos) y un icono por hoja sería ilegible. El mapa es auxiliar; si
 * falta un id, el ítem se dibuja sin icono y la app no se rompe.
 */
const NAV_ICONS: Record<string, React.ReactNode> = {
  home: <FaGaugeHigh size={14} />,
  reception: <FaTruckFast size={14} />,
  fulfillment: <FaTruckFast size={14} />,
  warehouse: <FaWarehouse size={14} />,
  settings: <FaGear size={14} />,
};

/**
 * Chrome de la aplicación con sesión ya confirmada.
 *
 * Se separa de `AppShell` porque los hooks (traducción, ruta, permisos) no pueden
 * ejecutarse después de un `return` temprano, y ese `return` es justamente lo que
 * evita montar el menú sin permisos.
 */
const AuthenticatedShell = ({
  user,
  children,
}: {
  user: AuthUser;
  children: React.ReactNode;
}): React.ReactNode => {
  const { t } = useTranslation(["common"]);
  const navigate = useNavigate();
  const { pathname } = useLocation();

  const isActive = useCallback(
    (screen: AppScreen): boolean => matchesScreenPath(pathname, screen),
    [pathname]
  );

  const navigationItems: ITNavigationItem[] = buildNavigationItems(
    APP_SCREENS,
    user.scopes as PermissionMap,
    user.roles[0] ?? null,
    {
      translate: (labelKey) => t(labelKey),
      icons: NAV_ICONS,
      isActive,
      goTo: (path) => navigate(path),
    }
  );

  const sidebar: ITSidebarProps = { navigationItems, isCollapsed: true };

  const topBar: ITLayoutProps["topBar"] = {
    logoText: t("app.name"),
    userMenu: {
      userName: user.name,
      userEmail: user.username,
      menuItems: [
        {
          label: t("nav.logout"),
          onClick: () => {
            void sessionStore.logout().then(() => navigate("/login", { replace: true }));
          },
        },
      ],
    },
  };

  return (
    <ITLayout topBar={topBar} sidebar={sidebar}>
      {children}
    </ITLayout>
  );
};

/**
 * `AppShell`: layout con menú **generado desde los permisos** de la sesión.
 *
 * Releva al `Layout.tsx` del legacy, donde el menú estaba escrito a mano,
 * duplicado en `Home.tsx` y sin filtrar por permisos (`NavItem` usaba `<a href>`,
 * que además recargaba la página y perdía la sesión). Aquí el menú es una
 * función de los permisos: si cambian, cambia el menú y no se toca código.
 */
export const AppShell = ({ children }: { children: React.ReactNode }): React.ReactNode => {
  const session = useSession();

  // Sin sesión **confirmada** no se monta el chrome (sidebar y topbar): mientras
  // `/auth/me` está en vuelo, o en la pantalla de acceso, no hay menú que
  // mostrar. Montarlo con permisos vacíos dibujaría un menú a medias y el
  // usuario vería aparecer y desaparecer enlaces.
  if (session.status !== "authenticated" || !session.user) {
    return <>{children}</>;
  }

  return <AuthenticatedShell user={session.user}>{children}</AuthenticatedShell>;
};
