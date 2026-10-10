import { ITBadget, ITInput, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { fulfillmentApi, type OrderHeader } from "@entities/fulfillment";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

/**
 * Surtido: las órdenes y su estado de exportación. Sin ERP conectado, el intento sale `FAILED` con
 * su motivo: la pantalla lo dice tal cual, en lugar de mostrar una exportación que no ocurrió.
 */
export default function OrdersPage() {
  const [search, setSearch] = useState("");
  const q = useDebouncedValue(search.trim(), 400);
  const externalFilters = useMemo(() => (q ? { q } : undefined), [q]);

  const columns: Column<OrderHeader>[] = [
    { key: "orderNumber", label: "Orden", type: "string" },
    { key: "status", label: "Estado", type: "string" },
    { key: "totalLines", label: "Líneas", type: "number" },
    { key: "packagedLines", label: "Empacadas", type: "number" },
    {
      key: "exportStatus",
      label: "Exportación",
      type: "string",
      render: (row) =>
        row.exportStatus ? (
          <ITBadget
            label={row.exportStatus === "FAILED" ? `Fallida · ${row.exportAttempts}` : row.exportStatus}
            color={row.exportStatus === "SENT" ? "success" : row.exportStatus === "FAILED" ? "danger" : "secondary"}
            size="sm"
          />
        ) : (
          "—"
        ),
    },
    { key: "exportLastError", label: "Motivo del fallo", type: "string", render: (row) => row.exportLastError ?? "—" },
  ];

  return (
    <Page title="Surtido" description="Órdenes, su empaque y su exportación al ERP." breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Surtido" }]}>
      <div className="mb-3 w-72">
        <ITInput size="sm" name="q" label="Buscar orden" value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>
      <DataTable<OrderHeader>
        columns={columns}
        fetchPage={(query) => fulfillmentApi.orders(query, {})}
        externalFilters={externalFilters}
      />
    </Page>
  );
}
