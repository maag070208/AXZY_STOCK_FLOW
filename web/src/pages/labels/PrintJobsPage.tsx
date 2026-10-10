import { ITBadget, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { labelsApi, type PrintJob } from "@entities/labels";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

const STATUSES = ["PENDING", "PRINTED", "FAILED"];

/**
 * La cola de impresión. Una etiqueta sale `PRINTED` solo cuando la impresora lo confirma: pedirla
 * no la marca como impresa. Un fallo se queda visible con su motivo y su número de intento.
 */
export default function PrintJobsPage() {
  const [status, setStatus] = useState("");
  const externalFilters = useMemo(() => (status ? { status } : undefined), [status]);

  const columns: Column<PrintJob>[] = [
    { key: "createdAt", label: "Pedida", type: "string", render: (row) => new Date(row.createdAt).toLocaleString("es-MX") },
    { key: "templateCode", label: "Plantilla", type: "string" },
    { key: "copies", label: "Copias", type: "number" },
    {
      key: "status",
      label: "Estado",
      type: "string",
      render: (row) => (
        <ITBadget
          label={row.status}
          color={row.status === "PRINTED" ? "success" : row.status === "FAILED" ? "danger" : "warning"}
          size="sm"
        />
      ),
    },
    { key: "attempts", label: "Intentos", type: "number" },
    { key: "printedAt", label: "Impresa", type: "string", render: (row) => (row.printedAt ? new Date(row.printedAt).toLocaleString("es-MX") : "—") },
    { key: "lastError", label: "Motivo del fallo", type: "string", render: (row) => row.lastError ?? "—" },
    { key: "reprintedFromId", label: "Reimpresión de", type: "number", render: (row) => (row.reprintedFromId ? `#${row.reprintedFromId}` : "—") },
  ];

  return (
    <Page title="Cola de impresión" description="Etiquetas pedidas, impresas y fallidas." breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Etiquetas" }]}>
      <div className="mb-3 w-56">
        <ITSelect size="sm" name="status" label="Estado" value={status} placeholder="Todos"
          options={STATUSES.map((value) => ({ value, label: value }))} onChange={(e) => setStatus(e.target.value)} />
      </div>
      <DataTable<PrintJob>
        columns={columns}
        fetchPage={(query) => labelsApi.printJobs(query, {})}
        externalFilters={externalFilters}
      />
    </Page>
  );
}
