import { useNavigate } from "react-router-dom";
import { FaClipboardList, FaExchangeAlt, FaTags, FaBoxes } from "react-icons/fa";
import { useSelector } from "react-redux";
import type { RootState } from "@app/store";
import { MENU } from "@app/navigation";
import { can } from "@entities/session";
import KpiTile from "@shared/ui/kpi-tile/KpiTile";
import PanelCard from "@shared/ui/panel-card/PanelCard";
import { Page } from "@shared/ui/page/Page";
import { useHomeSummary } from "./useHomeSummary";

/**
 * Inicio: lo que pide atención hoy y los accesos a cada módulo.
 *
 * Las cifras y las listas salen de los mismos endpoints que sus pantallas, y solo se piden las que el
 * usuario puede ver. Una cifra que no se pudo cargar muestra un guion, nunca un cero.
 */
export default function HomePage() {
  const navigate = useNavigate();
  const user = useSelector((state: RootState) => state.auth.user);
  const { summary, loading } = useHomeSummary();

  const accesses = MENU.filter((entry) => entry.id !== "home" && (entry.permission === null || can(user, entry.permission)));

  return (
    <Page
      title="Inicio"
      description="Lo que pide atención hoy, y a dónde ir a resolverlo."
      breadcrumbs={[{ label: "Inicio" }]}
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {can(user, "product.view") && (
            <KpiTile label="Productos" value={loading ? null : summary.products} icon={<FaBoxes size={18} />} tone="emerald" hint="En el catálogo" onClick={() => navigate("/products")} />
          )}
          {can(user, "invoice.view") && (
            <KpiTile label="Por aprobar" value={loading ? null : summary.pendingApproval} icon={<FaClipboardList size={18} />} tone="amber" hint="Facturas con diferencias" onClick={() => navigate("/invoices")} />
          )}
          {can(user, "transit.view") && (
            <KpiTile label="En tránsito" value={loading ? null : summary.inTransit} icon={<FaExchangeAlt size={18} />} tone="sky" hint="Traslados despachados" onClick={() => navigate("/transfers")} />
          )}
          {can(user, "label.queue_view") && (
            <KpiTile label="Etiquetas fallidas" value={loading ? null : summary.failedLabels} icon={<FaTags size={18} />} tone="rose" hint="Pendientes de reimprimir" onClick={() => navigate("/labels")} />
          )}
        </div>

        {summary.error && <p className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-[12px] text-amber-800">{summary.error}</p>}

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          {can(user, "invoice.view") && (
            <PanelCard
              title="Pendientes de aprobación"
              description="Facturas con diferencias que un supervisor debe decidir."
              actions={<button type="button" className="text-[12px] font-semibold text-sky-700 hover:underline" onClick={() => navigate("/invoices")}>Ver todo</button>}
            >
              {summary.pendingList.length === 0 ? (
                <p className="text-[12px] text-slate-500">No hay facturas esperando una decisión.</p>
              ) : (
                <ul className="flex flex-col divide-y divide-slate-100">
                  {summary.pendingList.map((row) => (
                    <li key={row.invoiceId}>
                      <button type="button" onClick={() => navigate(`/invoices/${row.invoiceId}`)} className="flex w-full items-center justify-between gap-3 py-2 text-left hover:bg-slate-50">
                        <span className="truncate text-[13px] font-semibold text-slate-700">Factura {row.invoiceNumber}</span>
                        <span className="shrink-0 text-[12px] tabular-nums text-slate-500">{row.pendingLines} líneas pendientes</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </PanelCard>
          )}

          {can(user, "transit.view") && (
            <PanelCard
              title="En tránsito"
              description="Mercancía despachada que todavía no llega a su bodega."
              actions={<button type="button" className="text-[12px] font-semibold text-sky-700 hover:underline" onClick={() => navigate("/transfers")}>Ver todo</button>}
            >
              {summary.transitList.length === 0 ? (
                <p className="text-[12px] text-slate-500">Nada en camino.</p>
              ) : (
                <ul className="flex flex-col divide-y divide-slate-100">
                  {summary.transitList.map((row) => (
                    <li key={row.id}>
                      <button type="button" onClick={() => navigate(`/transfers/${row.id}`)} className="flex w-full items-center justify-between gap-3 py-2 text-left hover:bg-slate-50">
                        <span className="truncate text-[13px] font-semibold text-slate-700">{row.code} · {row.fromWarehouseCode} → {row.toWarehouseCode}</span>
                        <span className="shrink-0 text-[12px] tabular-nums text-slate-500">{row.daysInTransit} días</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </PanelCard>
          )}
        </div>

        <PanelCard title="Accesos" description="Los módulos a los que tienes permiso.">
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-4">
            {accesses.map((entry) => (
              <button
                key={entry.id}
                type="button"
                onClick={() => navigate(entry.path)}
                className="flex items-center gap-3 rounded-lg border border-slate-200 px-4 py-3 text-left text-[13px] font-semibold text-slate-700 transition hover:border-sky-300 hover:bg-sky-50/40"
              >
                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-slate-600">{entry.icon}</span>
                {entry.label}
              </button>
            ))}
          </div>
        </PanelCard>
      </div>
    </Page>
  );
}
