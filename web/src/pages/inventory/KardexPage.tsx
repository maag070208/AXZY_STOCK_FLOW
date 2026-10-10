import { ITDatePicker, ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { inventoryApi, type KardexLine } from "@entities/inventory";
import { formatQuantity } from "@shared/lib/format";
import { asDate, toIsoDay } from "@shared/lib/day";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import { FaHistory } from "react-icons/fa";
import i18n from "@shared/i18n";

/**
 * The stock ledger (kardex): the book of movements, in reverse chronological order. Read-only: a movement
 * is not edited or deleted, and correcting means adding another one (ADR-0015). The date filters travel as
 * `YYYY-MM-DD`, the business day, which is what the API accepts.
 */
export default function KardexPage() {
  const [productCode, setProductCode] = useState("");
  const [from, setFrom] = useState<Date | null>(null);
  const [to, setTo] = useState<Date | null>(null);
  const product = useDebouncedValue(productCode.trim(), 400);

  const externalFilters = useMemo(() => {
    const fromDay = toIsoDay(from);
    const toDay = toIsoDay(to);
    return {
      ...(product ? { productCode: product } : {}),
      ...(fromDay ? { from: fromDay } : {}),
      ...(toDay ? { to: toDay } : {}),
    };
  }, [product, from, to]);

  const columns: Column<KardexLine>[] = [
    { key: "movedAt", label: i18n.t("common.date"), type: "string", render: (row) => new Date(row.movedAt).toLocaleString("en-US") },
    { key: "productCode", label: i18n.t("common.code"), type: "string" },
    { key: "moveType", label: i18n.t("common.movement"), type: "string" },
    { key: "units", label: i18n.t("common.units"), type: "number", render: (row) => formatQuantity(row.units) },
    { key: "quantityAfter", label: i18n.t("inventory.balanceAfter"), type: "number", render: (row) => formatQuantity(row.quantityAfter) },
    { key: "fromLocationDisplayName", label: i18n.t("common.from"), type: "string", render: (row) => row.fromLocationDisplayName ?? "—" },
    { key: "toLocationDisplayName", label: i18n.t("common.to"), type: "string", render: (row) => row.toLocationDisplayName ?? "—" },
    { key: "userName", label: i18n.t("auth.username"), type: "string", render: (row) => row.userName ?? "—" },
  ];

  return (
    <Page icon={<FaHistory size={18} />}
      title={i18n.t("nav.ledger")}
      description={i18n.t("inventory.ledgerDescription")}
      breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.ledger") }]}
    >
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="w-56">
          <ITInput size="sm" name="productCode" label={i18n.t("inventory.productCode")} value={productCode} onChange={(e) => setProductCode(e.target.value)} />
        </div>
        <div className="w-44">
          <ITDatePicker name="from" label={i18n.t("common.from")} value={from ?? undefined} onChange={(e) => setFrom(asDate(e.target.value))} />
        </div>
        <div className="w-44">
          <ITDatePicker name="to" label={i18n.t("common.to")} value={to ?? undefined} onChange={(e) => setTo(asDate(e.target.value))} />
        </div>
      </div>
      <DataTable<KardexLine>
        columns={columns}
        fetchPage={(query) => inventoryApi.kardex(query, {})}
        externalFilters={externalFilters}
      />
    </Page>
  );
}
