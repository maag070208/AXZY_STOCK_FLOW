import type { ReactNode } from "react";

interface PanelCardProps {
  title: string;
  /** Texto de apoyo bajo el título: qué muestra el panel y por qué importa. */
  description?: string;
  /** Acciones a la derecha del encabezado, como «Ver todo». */
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * Una sección con título, descripción y acciones. Mismo estilo que el panel de CYC: tarjeta blanca,
 * borde fino y encabezado con el texto de apoyo en gris.
 */
export default function PanelCard({ title, description, actions, children }: PanelCardProps) {
  return (
    <section className="flex flex-col rounded-xl border border-slate-200 bg-white p-5">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="text-[15px] font-semibold text-slate-900">{title}</h2>
          {description && <p className="mt-0.5 text-[12px] text-slate-500">{description}</p>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </header>
      {children}
    </section>
  );
}
