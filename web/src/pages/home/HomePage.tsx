import { useNavigate } from "react-router-dom";
import { FaClipboardList, FaExchangeAlt, FaTags, FaBoxes, FaHome } from "react-icons/fa";
import { useSelector } from "react-redux";
import type { RootState } from "@app/store";
import { MENU } from "@app/navigation";
import { can } from "@entities/session";
import KpiTile from "@shared/ui/kpi-tile/KpiTile";
import PanelCard from "@shared/ui/panel-card/PanelCard";
import { Page } from "@shared/ui/page/Page";
import { useHomeSummary } from "./useHomeSummary";
import i18n from "@shared/i18n";

/**
 * Home: what needs attention today and the shortcuts to each module.
 *
 * The figures and lists come from the same endpoints as their screens, and only those the
 * user can see. A figure that could not be loaded shows a dash, never a zero.
 */
export default function HomePage() {
  const navigate = useNavigate();
  const user = useSelector((state: RootState) => state.auth.user);
  const { summary, loading } = useHomeSummary();

  const accesses = MENU.filter((entry) => entry.id !== "home" && (entry.permission === null || can(user, entry.permission)));

  return (
    <Page icon={<FaHome size={18} />}
      title={i18n.t("nav.home")}
      description={i18n.t("home.description")}
      breadcrumbs={[{ label: i18n.t("nav.home") }]}
    >
      <div className="flex flex-col gap-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
          {can(user, "product.view") && (
            <KpiTile label={i18n.t("nav.products")} value={loading ? null : summary.products} icon={<FaBoxes size={18} />} tone="emerald" hint={i18n.t("home.kpiProductsHint")} onClick={() => navigate("/products")} />
          )}
          {can(user, "invoice.view") && (
            <KpiTile label={i18n.t("home.kpiPending")} value={loading ? null : summary.pendingApproval} icon={<FaClipboardList size={18} />} tone="amber" hint={i18n.t("home.kpiPendingHint")} onClick={() => navigate("/invoices")} />
          )}
          {can(user, "transit.view") && (
            <KpiTile label={i18n.t("transfers.inTransit")} value={loading ? null : summary.inTransit} icon={<FaExchangeAlt size={18} />} tone="sky" hint={i18n.t("home.kpiTransitHint")} onClick={() => navigate("/transfers")} />
          )}
          {can(user, "label.queue_view") && (
            <KpiTile label={i18n.t("home.kpiFailedLabels")} value={loading ? null : summary.failedLabels} icon={<FaTags size={18} />} tone="rose" hint={i18n.t("home.kpiFailedLabelsHint")} onClick={() => navigate("/labels")} />
          )}
        </div>

        {summary.error && <p className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-[12px] text-amber-800">{summary.error}</p>}

        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          {can(user, "invoice.view") && (
            <PanelCard
              title={i18n.t("receiving.pendingApproval")}
              description={i18n.t("home.pendingDescription")}
              actions={<button type="button" className="text-[12px] font-semibold text-sky-700 hover:underline" onClick={() => navigate("/invoices")}>{i18n.t("home.seeAll")}</button>}
            >
              {summary.pendingList.length === 0 ? (
                <p className="text-[12px] text-slate-500">{i18n.t("home.noPending")}</p>
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
              title={i18n.t("transfers.inTransit")}
              description={i18n.t("home.transitDescription")}
              actions={<button type="button" className="text-[12px] font-semibold text-sky-700 hover:underline" onClick={() => navigate("/transfers")}>{i18n.t("home.seeAll")}</button>}
            >
              {summary.transitList.length === 0 ? (
                <p className="text-[12px] text-slate-500">{i18n.t("home.noTransit")}</p>
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

        <PanelCard title={i18n.t("home.accesses")} description={i18n.t("home.accessesDescription")}>
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
