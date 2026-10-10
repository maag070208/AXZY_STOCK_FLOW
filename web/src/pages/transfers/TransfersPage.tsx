import { ITBadget, ITSelect, ITText, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { transfersApi, type InTransit, type TransferHeader } from "@entities/transfers";
import { formatQuantity } from "@shared/lib/format";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

const STATUSES = ["DRAFT", "DISPATCHED", "RECEIVED", "CANCELLED"];

/**
 * Traslados entre bodegas y la mercancía en camino. El tránsito cuenta los días desde el despacho
 * con el reloj del servidor; la pantalla solo lo muestra. Un traslado no pierde mercancía por estar
 * en el camión: sigue contando hasta que se recibe.
 */
export default function TransfersPage() {
  const navigate = useNavigate();
  const [status, setStatus] = useState("");
  const externalFilters = useMemo(() => (status ? { status } : undefined), [status]);

  const columns: Column<TransferHeader>[] = [
    { key: "code", label: "Código", type: "string" },
    { key: "fromWarehouseCode", label: "Desde", type: "string" },
    { key: "toWarehouseCode", label: "Hasta", type: "string" },
    { key: "status", label: "Estado", type: "string", render: (row) => <ITBadget label={row.status} color="secondary" size="sm" /> },
    { key: "lineCount", label: "Líneas", type: "number" },
    { key: "totalUnits", label: "Unidades", type: "number", render: (row) => formatQuantity(row.totalUnits) },
    { key: "dispatchedByName", label: "Despachó", type: "string", render: (row) => row.dispatchedByName ?? "—" },
  ];

  const transitColumns: Column<InTransit>[] = [
    { key: "code", label: "Código", type: "string" },
    { key: "fromWarehouseCode", label: "Desde", type: "string" },
    { key: "toWarehouseCode", label: "Hasta", type: "string" },
    { key: "totalUnits", label: "Unidades", type: "number", render: (row) => formatQuantity(row.totalUnits) },
    { key: "daysInTransit", label: "Días en camino", type: "number" },
  ];

  return (
    <Page title="Traslados" description="Mercancía entre bodegas y la que va en camino." breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Traslados" }]}>
      <div className="mb-3 w-56">
        <ITSelect size="sm" name="status" label="Estado" value={status} placeholder="Todos"
          options={STATUSES.map((value) => ({ value, label: value }))} onChange={(e) => setStatus(e.target.value)} />
      </div>
      <DataTable<TransferHeader>
        columns={columns}
        onRowClick={(row) => navigate(`/transfers/${row.id}`)}
        fetchPage={(query) => transfersApi.transfers(query, {})}
        externalFilters={externalFilters}
      />
      <ITText className="mb-2 mt-6 font-semibold">En tránsito</ITText>
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
