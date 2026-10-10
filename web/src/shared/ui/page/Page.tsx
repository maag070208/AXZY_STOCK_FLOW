import { ITPage, type ITBreadcrumbItem } from "@axzydev/axzy_ui_system";
import type { ReactNode } from "react";

interface PageProps {
  title: string;
  description?: string;
  /** Header icon: identifies the module at a glance, as in CYC. */
  icon?: ReactNode;
  breadcrumbs?: ITBreadcrumbItem[];
  /** Buttons on the right of the header: the main action of the screen goes here. */
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * The wrapper of `ITPage` for StockFlow (docs/arquitectura/web-react.md §3.2).
 *
 * Defines **once** the look of each screen: full width (the menu hides itself, so the
 * content takes advantage of the space) and a fixed gutter. A screen does not repeat kit classes.
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
