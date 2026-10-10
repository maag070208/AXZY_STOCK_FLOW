import { ITBadget, ITButton, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import ReasonDialog from "@features/catalog/ReasonDialog";
import { can } from "@entities/session";
import { labelsApi, type PrintJob } from "@entities/labels";
import { ApiError } from "@shared/api/types";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

const STATUSES = ["PENDING", "PRINTED", "FAILED"];

/**
 * La cola de impresión. Una etiqueta sale `PRINTED` solo cuando la impresora lo confirma: pedirla
 * no la marca como impresa. Un fallo se queda visible con su motivo y su número de intento.
 *
 * Reimprimir pide un motivo y crea un trabajo **nuevo** enlazado al original; el original no cambia.
 */
export default function PrintJobsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);
  const [status, setStatus] = useState("");
  const [reprinting, setReprinting] = useState<PrintJob | null>(null);
  const [reprintError, setReprintError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);
  const externalFilters = useMemo(() => (status ? { status } : undefined), [status]);

  const reprint = async (reason: string) => {
    if (!reprinting) return;
    setBusy(true);
    setReprintError(null);
    try {
      await labelsApi.reprint(reprinting.id, reason);
      setReprinting(null);
      dispatch(showToast("Reimpresión pedida. Aparece en la cola como un trabajo nuevo.", "success"));
      setVersion((current) => current + 1);
    } catch (caught) {
      setReprintError(caught instanceof ApiError ? caught.message : "No se pudo pedir la reimpresión.");
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<PrintJob>[] = [
    { key: "createdAt", label: "Pedida", type: "string", render: (row) => new Date(row.createdAt).toLocaleString("es-MX") },
    { key: "templateCode", label: "Plantilla", type: "string" },
    { key: "copies", label: "Copias", type: "number" },
    {
      key: "status",
      label: "Estado",
      type: "string",
      render: (row) => <ITBadget label={row.status} color="secondary" size="sm" />,
    },
    { key: "attempts", label: "Intentos", type: "number" },
    { key: "printedAt", label: "Impresa", type: "string", render: (row) => (row.printedAt ? new Date(row.printedAt).toLocaleString("es-MX") : "—") },
    { key: "lastError", label: "Motivo del fallo", type: "string", render: (row) => row.lastError ?? "—" },
    { key: "reprintedFromId", label: "Reimpresión de", type: "number", render: (row) => (row.reprintedFromId ? `#${row.reprintedFromId}` : "—") },
    ...(can(user, "label.reprint")
      ? [
          {
            key: "actions",
            label: "",
            type: "string" as const,
            sortable: false,
            render: (row: PrintJob) => <ITButton label="Reimprimir" size="sm" variant="outline" onClick={() => setReprinting(row)} />,
          },
        ]
      : []),
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
        reloadTrigger={version}
      />

      <ReasonDialog
        open={reprinting !== null}
        title="Reimprimir etiqueta"
        description={reprinting ? `Se pide otra copia de la etiqueta #${reprinting.id} (${reprinting.templateCode}). Gasta una etiqueta más.` : ""}
        confirmLabel="Reimprimir"
        placeholder="Por qué se reimprime"
        errorTitle="No se pudo reimprimir"
        busy={busy}
        error={reprintError}
        onCancel={() => setReprinting(null)}
        onConfirm={(reason) => void reprint(reason)}
      />
    </Page>
  );
}
