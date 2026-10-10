import { ITPage, type ITBreadcrumbItem } from "@axzydev/axzy_ui_system";
import type { ReactNode } from "react";

interface PageProps {
  title: string;
  description?: string;
  icon?: ReactNode;
  breadcrumbs?: ITBreadcrumbItem[];
  actions?: ReactNode;
  maxWidth?: "2xl" | "3xl" | "4xl" | "5xl" | "6xl" | "7xl";
  children: ReactNode;
}

/**
 * El envoltorio de `ITPage` para StockFlow (docs/arquitectura/web-react.md §3.2).
 *
 * Existe por un patrón concreto de CYC: la cadena `"m-0! px-4! max-w-screen!"`
 * copiada en cada página para pelear contra el padding del kit. Las banderas
 * `!` (importantes) son el síntoma de que el contenedor no estaba definido.
 *
 * Aquí se define **una vez**: el ancho y el padding de StockFlow viven en este
 * archivo, y una pantalla que necesite otro ancho pasa `maxWidth` en lugar de
 * reescribir clases.
 */
export function Page({ title, description, icon, breadcrumbs, actions, maxWidth = "7xl", children }: PageProps) {
  return (
    <ITPage
      title={title}
      description={description}
      icon={icon}
      breadcrumbs={breadcrumbs}
      actions={actions}
      maxWidth={maxWidth}
      // El padding lo pone el contenedor de abajo, no el kit: así el contenido
      // de una pantalla con tabla puede llegar al borde sin `!`.
      noPadding
    >
      <div className="px-4 pt-2 pb-6">{children}</div>
    </ITPage>
  );
}
