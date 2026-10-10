import type { ReactNode } from "react";

interface PanelCardProps {
  title: string;
  /** Support text under the title: what the panel shows and why it matters. */
  description?: string;
  /** Actions on the right of the header, such as "See all". */
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * A section with title, description and actions. Same style as the CYC panel: white card,
 * thin border and a header with the support text in grey.
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
