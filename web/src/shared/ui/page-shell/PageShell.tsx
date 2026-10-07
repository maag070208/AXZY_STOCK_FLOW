import { ITPage } from "@axzydev/axzy_ui_system";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { ReactNode } from "react";

/**
 * Envoltura de pantalla.
 *
 * Reutiliza `ITPage` del UI kit (título, migas, estados de carga/vacío/error ya
 * resueltos) y sólo agrega lo que la app necesita en todas las pantallas:
 * migas que vuelven al inicio y el `navigate(-1)` del botón de regreso.
 *
 * **No** se agregan estilos locales: el kit ya trae el layout y las capas de
 * Tailwind resuelven el espaciado.
 */
export const PageShell = ({
  title,
  description,
  actions,
  icon,
  children,
  breadcrumbLabel,
  showBack = true,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
  icon?: ReactNode;
  children: ReactNode;
  /** Etiqueta de la miga intermedia; si falta, sólo se muestra «Inicio» y el título. */
  breadcrumbLabel?: string;
  showBack?: boolean;
}): ReactNode => {
  const navigate = useNavigate();
  const { t } = useTranslation(["common"]);

  return (
    <ITPage
      title={title}
      {...(description ? { description } : {})}
      {...(icon ? { icon } : {})}
      {...(actions ? { actions } : {})}
      {...(showBack ? { backAction: () => navigate(-1) } : {})}
      breadcrumbs={[
        { label: t("nav.home"), onClick: () => navigate("/") },
        ...(breadcrumbLabel ? [{ label: breadcrumbLabel }] : []),
        { label: title },
      ]}
    >
      {children}
    </ITPage>
  );
};
