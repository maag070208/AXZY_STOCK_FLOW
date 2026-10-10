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
import { FaClipboardList } from "react-icons/fa";
import i18n from "@shared/i18n";

/**
 * Receiving: the invoices, and the inbox of those awaiting a supervisor's decision. The
 * the inbox is returned by the API without a pagination envelope (`{ items }`); here it is adapted to the same table
 * with a single page, because it is a bounded list and does not grow with the operation.
 *
 * A row opens the invoice; creating a new invoice is done in the dialog and, when it is registered, it opens
 * the invoice just created to start counting.
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
    { key: "invoiceNumber", label: i18n.t("receiving.invoice"), type: "string", sortable: false },
    { key: "receiptDate", label: i18n.t("nav.receiving"), type: "string" },
    { key: "status", label: i18n.t("common.status"), type: "string" },
    { key: "totalLines", label: i18n.t("receiving.lines"), type: "number" },
    {
      key: "pendingApprovalLines",
      label: i18n.t("receiving.pendingApprovalShort"),
      type: "number",
      render: (row) =>
        row.pendingApprovalLines > 0 ? <ITBadget label={String(row.pendingApprovalLines)} color="warning" size="sm" /> : "0",
    },
  ];

  const pendingColumns: Column<PendingApproval>[] = [
    { key: "invoiceNumber", label: i18n.t("receiving.invoice"), type: "string" },
    { key: "receiptDate", label: i18n.t("nav.receiving"), type: "string" },
    { key: "pendingLines", label: i18n.t("receiving.pendingLines"), type: "number" },
    { key: "totalDifference", label: i18n.t("receiving.totalDifference"), type: "number", render: (row) => formatQuantity(row.totalDifference) },
  ];

  const createInvoice = async (body: Parameters<typeof receivingApi.create>[0]) => {
    const created = await receivingApi.create(body);
    setCreating(false);
    dispatch(showToast(i18n.t("receiving.invoiceRegistered", { number: created.invoiceNumber }), "success"));
    navigate(`/invoices/${created.id}`);
  };

  return (
    <Page icon={<FaClipboardList size={18} />}
      title={i18n.t("nav.receiving")}
      description={i18n.t("receiving.invoicesDescription")}
      breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.receiving") }]}
      actions={
        can(user, "invoice.create") ? <ITButton label={i18n.t("receiving.newInvoice")} color="primary" onClick={() => setCreating(true)} /> : undefined
      }
    >
      <div className="mb-4 w-72">
        <ITInput size="sm" name="q" label={i18n.t("receiving.searchInvoice")} value={search} onChange={(e) => setSearch(e.target.value)} />
      </div>
      <ITText className="mb-2 font-semibold">{i18n.t("receiving.pendingApproval")}</ITText>
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
      <ITText className="mb-2 font-semibold">{i18n.t("receiving.invoices")}</ITText>
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
