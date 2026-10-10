import { ITBadget, ITSelect, ITText, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { transfersApi, type InTransit, type TransferHeader } from "@entities/transfers";
import { formatQuantity } from "@shared/lib/format";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import { FaExchangeAlt } from "react-icons/fa";
import i18n from "@shared/i18n";

const STATUSES = ["DRAFT", "DISPATCHED", "RECEIVED", "CANCELLED"];

/**
 * Transfers between warehouses and goods in transit. Transit counts the days from the dispatch
 * with the server clock; the screen only displays it. A transfer does not lose goods by being
 * in the truck: it keeps counting until it is received.
 */
export default function TransfersPage() {
  const navigate = useNavigate();
  const [status, setStatus] = useState("");
  const externalFilters = useMemo(() => (status ? { status } : undefined), [status]);

  const columns: Column<TransferHeader>[] = [
    { key: "code", label: i18n.t("common.code"), type: "string" },
    { key: "fromWarehouseCode", label: i18n.t("common.from"), type: "string" },
    { key: "toWarehouseCode", label: i18n.t("common.to"), type: "string" },
    { key: "status", label: i18n.t("common.status"), type: "string", render: (row) => <ITBadget label={row.status} color="secondary" size="sm" /> },
    { key: "lineCount", label: i18n.t("receiving.lines"), type: "number" },
    { key: "totalUnits", label: i18n.t("common.units"), type: "number", render: (row) => formatQuantity(row.totalUnits) },
    { key: "dispatchedByName", label: i18n.t("transfers.dispatchedBy"), type: "string", render: (row) => row.dispatchedByName ?? "—" },
  ];

  const transitColumns: Column<InTransit>[] = [
    { key: "code", label: i18n.t("common.code"), type: "string" },
    { key: "fromWarehouseCode", label: i18n.t("common.from"), type: "string" },
    { key: "toWarehouseCode", label: i18n.t("common.to"), type: "string" },
    { key: "totalUnits", label: i18n.t("common.units"), type: "number", render: (row) => formatQuantity(row.totalUnits) },
    { key: "daysInTransit", label: i18n.t("transfers.daysInTransit"), type: "number" },
  ];

  return (
    <Page icon={<FaExchangeAlt size={18} />} title={i18n.t("nav.transfers")} description={i18n.t("transfers.description")} breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.transfers") }]}>
      <div className="mb-3 w-56">
        <ITSelect size="sm" name="status" label={i18n.t("common.status")} value={status} placeholder={i18n.t("common.all")}
          options={STATUSES.map((value) => ({ value, label: value }))} onChange={(e) => setStatus(e.target.value)} />
      </div>
      <DataTable<TransferHeader>
        columns={columns}
        onRowClick={(row) => navigate(`/transfers/${row.id}`)}
        fetchPage={(query) => transfersApi.transfers(query, {})}
        externalFilters={externalFilters}
      />
      <ITText className="mb-2 mt-6 font-semibold">{i18n.t("transfers.inTransit")}</ITText>
      <DataTable<InTransit>
        columns={transitColumns}
        onRowClick={(row) => navigate(`/transfers/${row.id}`)}
        fetchPage={async () => {
          const items = await transfersApi.inTransit();
          return { items, page: 0, size: items.length, totalItems: items.length, totalPages: 1 };
        }}
      />
    </Page>
  );
}
