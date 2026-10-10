import { ITDatePicker, ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { inventoryApi, type KardexLine } from "@entities/inventory";
import { formatQuantity } from "@shared/lib/format";
import { asDate, toIsoDay } from "@shared/lib/day";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

/**
 * El kardex: el libro de movimientos, en orden cronológico inverso. Solo lectura: un movimiento
 * no se edita ni se borra, y corregir es añadir otro (ADR-0015). Los filtros de fecha viajan como
 * `AAAA-MM-DD`, el día de negocio, que es lo que la API acepta.
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
    { key: "movedAt", label: "Fecha", type: "string", render: (row) => new Date(row.movedAt).toLocaleString("es-MX") },
    { key: "productCode", label: "Código", type: "string" },
    { key: "moveType", label: "Movimiento", type: "string" },
    { key: "units", label: "Unidades", type: "number", render: (row) => formatQuantity(row.units) },
    { key: "quantityAfter", label: "Saldo después", type: "number", render: (row) => formatQuantity(row.quantityAfter) },
    { key: "fromLocationDisplayName", label: "Desde", type: "string", render: (row) => row.fromLocationDisplayName ?? "—" },
    { key: "toLocationDisplayName", label: "Hasta", type: "string", render: (row) => row.toLocationDisplayName ?? "—" },
    { key: "userName", label: "Usuario", type: "string", render: (row) => row.userName ?? "—" },
  ];

  return (
    <Page
      title="Kardex"
      description="El libro de movimientos. Cada cambio de existencias aparece aquí."
      breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Kardex" }]}
    >
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="w-56">
          <ITInput size="sm" name="productCode" label="Código de producto" value={productCode} onChange={(e) => setProductCode(e.target.value)} />
        </div>
        <div className="w-44">
          <ITDatePicker name="from" label="Desde" value={from ?? undefined} onChange={(e) => setFrom(asDate(e.target.value))} />
        </div>
        <div className="w-44">
          <ITDatePicker name="to" label="Hasta" value={to ?? undefined} onChange={(e) => setTo(asDate(e.target.value))} />
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
