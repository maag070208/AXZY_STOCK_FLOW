import { ITBadget, ITButton, ITInput, ITText, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import NewInvoiceDialog from "@features/receiving/NewInvoiceDialog";
import { can } from "@entities/session";
import { receivingApi, type InvoiceHeader, type PendingApproval } from "@entities/receiving";
import { formatQuantity } from "@shared/lib/format";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

/**
 * Recepción: las facturas, y la bandeja de las que esperan la decisión de un supervisor. La
 * bandeja la devuelve la API sin sobre de paginación (`{ items }`); aquí se adapta a la misma tabla
 * con una sola página, porque es una lista acotada y no crece con la operación.
 *
 * Una fila abre la factura; el alta de una factura nueva va en el diálogo y, al registrarse, abre
 * la factura recién creada para empezar a contar.
 */
export default function InvoicesPage() {
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);
  const [search, setSearch] = useState("");
  const [creating, setCreating] = useState(false);
  const q = useDebouncedValue(search.trim(), 400);

  const externalFilters = useMemo(() => (q ? { q } : undefined), [q]);

  const invoiceColumns: Column<InvoiceHeader>[] = [
    { key: "invoiceNumber", label: "Factura", type: "string", sortable: false },
    { key: "receiptDate", label: "Recepción", type: "string" },
    { key: "status", label: "Estado", type: "string" },
    { key: "totalLines", label: "Líneas", type: "number" },
    {
      key: "pendingApprovalLines",
      label: "Pendientes de aprobar",
      type: "number",
      render: (row) =>
        row.pendingApprovalLines > 0 ? <ITBadget label={String(row.pendingApprovalLines)} color="warning" size="sm" /> : "0",
    },
  ];

  const pendingColumns: Column<PendingApproval>[] = [
    { key: "invoiceNumber", label: "Factura", type: "string" },
    { key: "receiptDate", label: "Recepción", type: "string" },
    { key: "pendingLines", label: "Líneas pendientes", type: "number" },
    { key: "totalDifference", label: "Diferencia total", type: "number", render: (row) => formatQuantity(row.totalDifference) },
  ];

  const createInvoice = async (body: Parameters<typeof receivingApi.create>[0]) => {
    const created = await receivingApi.create(body);
    setCreating(false);
    dispatch(showToast(`Factura ${created.invoiceNumber} registrada.`, "success"));
    navigate(`/invoices/${created.id}`);
  };

  return (
    <Page
      title="Recepción"
      description="Facturas recibidas y las que esperan la aprobación de un supervisor."
      breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Recepción" }]}
      actions={
        can(user, "invoice.create") ? <ITButton label="Nueva factura" color="primary" onClick={() => setCreating(true)} /> : undefined
      }
    >
      <div className="mb-4 w-72">
        <ITInput size="sm" name="q" label="Buscar factura" value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>
      <ITText className="mb-2 font-semibold">Pendientes de aprobación</ITText>
      <div className="mb-6">
        <DataTable<PendingApproval>
          columns={pendingColumns}
          onRowClick={(row) => navigate(`/invoices/${row.invoiceId}`)}
          fetchPage={async () => {
            const { items } = await receivingApi.pendingApproval();
            return { items, page: 0, size: items.length, totalItems: items.length, totalPages: 1 };
          }}
        />
      </div>
      <ITText className="mb-2 font-semibold">Facturas</ITText>
      <DataTable<InvoiceHeader>
        columns={invoiceColumns}
        onRowClick={(row) => navigate(`/invoices/${row.id}`)}
        fetchPage={(query) => receivingApi.invoices(query, {})}
        externalFilters={externalFilters}
      />

      <NewInvoiceDialog open={creating} onClose={() => setCreating(false)} onSubmit={createInvoice} />
    </Page>
  );
}
