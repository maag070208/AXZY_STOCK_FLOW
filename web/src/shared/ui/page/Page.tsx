import { ITPage, type ITBreadcrumbItem } from "@axzydev/axzy_ui_system";
import type { ReactNode } from "react";

interface PageProps {
  title: string;
  description?: string;
  /** Icono del encabezado: identifica el módulo de un vistazo, como en CYC. */
  icon?: ReactNode;
  breadcrumbs?: ITBreadcrumbItem[];
  /** Botones a la derecha del encabezado: la acción principal de la pantalla va aquí. */
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * El envoltorio de `ITPage` para StockFlow (docs/arquitectura/web-react.md §3.2).
 *
 * Define **una vez** el aspecto de cada pantalla: ancho completo (el menú se oculta solo, así que el
 * contenido es quien aprovecha el espacio) y un gutter fijo. Una pantalla no repite clases del kit.
 */
export function Page({ title, description, icon, breadcrumbs, actions, children }: PageProps) {
  return (
    <ITPage
      title={title}
      description={description}
      icon={icon}
      breadcrumbs={breadcrumbs}
      actions={actions}
      noPadding
      className="m-0! px-4! max-w-screen!"
    >
      <div className="pt-4 pb-8">{children}</div>
    </ITPage>
  );
}
