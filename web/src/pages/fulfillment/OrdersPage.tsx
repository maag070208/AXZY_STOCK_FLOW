import { ITBadget, ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { fulfillmentApi, type OrderHeader } from "@entities/fulfillment";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import { FaTruck } from "react-icons/fa";
import i18n from "@shared/i18n";

/**
 * Fulfillment: the orders and their export status. Without a connected ERP, the attempt comes out `FAILED` with
 * its reason: the screen says so as it is, instead of showing an export that did not happen.
 */
export default function OrdersPage() {
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search.trim(), 400);
  const externalFilters = useMemo(() => (q ? { q } : undefined), [q]);

  const columns: Column<OrderHeader>[] = [
    { key: "orderNumber", label: i18n.t("fulfillment.order"), type: "string" },
    { key: "status", label: i18n.t("common.status"), type: "string" },
    { key: "totalLines", label: i18n.t("receiving.lines"), type: "number" },
    { key: "packagedLines", label: i18n.t("fulfillment.packed"), type: "number" },
    {
      key: "exportStatus",
      label: i18n.t("fulfillment.export"),
      type: "string",
      render: (row) =>
        row.exportStatus ? (
          <ITBadget
            label={row.exportStatus === "FAILED" ? i18n.t("fulfillment.failedAttempts", { attempts: row.exportAttempts }) : row.exportStatus}
            color={row.exportStatus === "SENT" ? "success" : row.exportStatus === "FAILED" ? "danger" : "secondary"}
            size="sm"
          />
        ) : (
          "—"
        ),
    },
    { key: "exportLastError", label: i18n.t("fulfillment.failureReason"), type: "string", render: (row) => row.exportLastError ?? "—" },
  ];

  return (
    <Page icon={<FaTruck size={18} />} title={i18n.t("nav.fulfillment")} description={i18n.t("fulfillment.ordersDescription")} breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.fulfillment") }]}>
      <div className="mb-3 w-72">
        <ITInput size="sm" name="q" label={i18n.t("fulfillment.searchOrder")} value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>
      <DataTable<OrderHeader>
        columns={columns}
        onRowClick={(row) => navigate(`/orders/${row.id}`)}
        fetchPage={(query) => fulfillmentApi.orders(query, {})}
        externalFilters={externalFilters}
      />
    </Page>
  );
}
