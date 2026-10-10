import { ITAlert, ITBadget, ITButton, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate, useParams } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import CaptureLineDialog from "@features/receiving/CaptureLineDialog";
import DecisionDialog from "@features/receiving/DecisionDialog";
import { can } from "@entities/session";
import { isCountable, receivingApi, type CaptureBody, type DecisionBody, type InvoiceDetail, type InvoiceLine } from "@entities/receiving";

import { formatQuantity } from "@shared/lib/format";
import { Page } from "@shared/ui/page/Page";
import { FaClipboardList } from "react-icons/fa";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

/**
 * An invoice with its lines: the count of each one and the decisions for the whole invoice.
 *
 * The buttons are offered according to what the user can do **and** what the invoice allows. The
 * API decides the same way (and responds with the corresponding code); the button only avoids offering what would
 * va a fallar.
 */
export default function InvoiceDetailPage() {
  const { id } = useParams();
  const invoiceId = Number(id);
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);

  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);
  const [counting, setCounting] = useState<InvoiceLine | null>(null);
  const [deciding, setDeciding] = useState<"approve" | "reject" | null>(null);

  useEffect(() => {
    if (!Number.isInteger(invoiceId) || invoiceId <= 0) {
      setLoadError(i18n.t("receiving.notFound"));
      return;
    }
    let active = true;
    receivingApi
      .detail(invoiceId)
      .then((data) => {
        if (!active) return;
        setInvoice(data);
        setLoadError(null);
      })
      .catch((caught: unknown) => {
        if (active) setLoadError(describeError(caught, "receiving.loadFailed"));
      });
    return () => {
      active = false;
    };
  }, [invoiceId, version]);

  const refresh = () => setVersion((current) => current + 1);

  const saveCount = async (body: CaptureBody) => {
    if (!counting) return;
    await receivingApi.capture(invoiceId, counting.id, body);
    setCounting(null);
    dispatch(showToast(i18n.t("receiving.countSaved"), "success"));
    refresh();
  };

  const decide = async (body: DecisionBody) => {
    if (!deciding) return;
    if (deciding === "approve") {
      await receivingApi.approve(invoiceId, body);
      dispatch(showToast(i18n.t("receiving.discrepanciesApproved"), "success"));
    } else {
      await receivingApi.rejectDiscrepancy(invoiceId, body);
      dispatch(showToast(i18n.t("receiving.discrepanciesRejected"), "success"));
    }
    setDeciding(null);
    refresh();
  };

  /** Assigning oneself a line: the API leaves it under the caller's name and responds with the updated line. */
  const assignToMe = async (line: InvoiceLine) => {
    setBusy(true);
    setActionError(null);
    try {
      await receivingApi.assign(invoiceId, line.id, {});
      dispatch(showToast(i18n.t("receiving.lineAssigned"), "success"));
      refresh();
    } catch (caught) {
      setActionError(describeError(caught, "receiving.assignFailed"));
    } finally {
      setBusy(false);
    }
  };

  const close = async () => {
    setActionError(null);
    try {
      await receivingApi.close(invoiceId);
      dispatch(showToast(i18n.t("receiving.invoiceClosed"), "success"));
      refresh();
    } catch (caught) {
      setActionError(describeError(caught, "receiving.closeFailed"));
    }
  };

  const breadcrumbs = [
    { label: i18n.t("nav.home"), href: "#/" },
    { label: i18n.t("nav.receiving"), href: "#/invoices" },
    { label: invoice?.invoiceNumber ?? i18n.t("receiving.invoice") },
  ];

  if (loadError) {
    return (
      <Page icon={<FaClipboardList size={18} />} title={i18n.t("receiving.invoice")} breadcrumbs={breadcrumbs}>
        <ITAlert variant="error" title={i18n.t("receiving.openFailed")}>{loadError}</ITAlert>
      </Page>
    );
  }
  if (!invoice) {
    return (
      <Page title={i18n.t("receiving.invoice")} breadcrumbs={breadcrumbs}>
        <ITText muted>{i18n.t("receiving.loadingInvoice")}</ITText>
      </Page>
    );
  }

  const hasDiscrepancy = invoice.lines.some((line) => line.status === "DISCREPANCY");
  const closed = invoice.status === "COMPLETED" || invoice.status === "CANCELLED";

  return (
    <Page
      title={`${i18n.t("receiving.invoice")} ${invoice.invoiceNumber}`}
      description={i18n.t("receiving.receivedOn", { date: invoice.receiptDate, type: invoice.invoiceType === "NATIONAL" ? i18n.t("common.national") : i18n.t("common.international") })}
      breadcrumbs={breadcrumbs}
    >
      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITBadget label={invoice.status} color="secondary" size="sm" />
        <ITText muted className="text-sm">
          {invoice.totalLines} líneas · {invoice.pendingApprovalLines} pendientes de aprobar
        </ITText>
      </ITFlex>

      {actionError && <ITAlert variant="error" title={i18n.t("common.actionFailedShort")}>{actionError}</ITAlert>}

      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITButton label={i18n.t("receiving.backToReceiving")} variant="outline" onClick={() => navigate("/invoices")} />
        {can(user, "invoice.approve") && hasDiscrepancy && (
          <ITButton label={i18n.t("receiving.approveDiscrepancies")} color="primary" onClick={() => setDeciding("approve")} />
        )}
        {can(user, "invoice.reject") && hasDiscrepancy && (
          <ITButton label={i18n.t("receiving.rejectDiscrepancies")} variant="outline" color="danger" onClick={() => setDeciding("reject")} />
        )}
        {can(user, "invoice.close") && !closed && (
          <ITButton label={i18n.t("receiving.closeInvoice")} variant="outline" onClick={() => void close()} />
        )}
      </ITFlex>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] border-collapse text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="py-2 pr-3">{i18n.t("common.product")}</th>
              <th className="py-2 pr-3 text-right">Facturado</th>
              <th className="py-2 pr-3 text-right">Contado</th>
              <th className="py-2 pr-3 text-right">Diferencia</th>
              <th className="py-2 pr-3">{i18n.t("common.status")}</th>
              <th className="py-2 pr-3" />
            </tr>
          </thead>
          <tbody>
            {invoice.lines.map((line) => (
              <tr key={line.id} className="border-b">
                <td className="py-2 pr-3">
                  <div className="font-medium">{line.productCode}</div>
                  <div className="text-xs text-slate-500">{line.productName}</div>
                </td>
                <td className="py-2 pr-3 text-right">{formatQuantity(line.quantity)}</td>
                <td className="py-2 pr-3 text-right">{line.physicalQuantity === null ? "—" : formatQuantity(line.physicalQuantity)}</td>
                <td className="py-2 pr-3 text-right">{line.difference === null ? "—" : formatQuantity(line.difference)}</td>
                <td className="py-2 pr-3">
                  <ITBadget label={line.status} color="secondary" size="sm" />
                </td>
                <td className="py-2 pr-3 text-right">
                  <ITFlex gap={2} justify="end" wrap="wrap">
                    {can(user, "invoice.assign") && line.status === "PENDING" && (
                      <ITButton label={i18n.t("receiving.assignToMe")} size="sm" variant="outline" disabled={busy} onClick={() => void assignToMe(line)} />
                    )}
                    {can(user, "invoice.capture") && isCountable(line.status) && (
                      <ITButton label={i18n.t("receiving.count")} size="sm" variant="outline" onClick={() => setCounting(line)} />
                    )}
                  </ITFlex>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <CaptureLineDialog open={counting !== null} line={counting} onClose={() => setCounting(null)} onSubmit={saveCount} />

      <DecisionDialog
        open={deciding !== null}
        title={deciding === "approve" ? i18n.t("receiving.approveDiscrepancies") : i18n.t("receiving.rejectDiscrepancies")}
        description={
          deciding === "approve"
            ? i18n.t("receiving.approveHint")
            : i18n.t("receiving.rejectHint")
        }
        confirmLabel={deciding === "approve" ? i18n.t("receiving.approve") : i18n.t("receiving.reject")}
        onClose={() => setDeciding(null)}
        onSubmit={decide}
      />
    </Page>
  );
}
