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
import { ApiError } from "@shared/api/types";
import { formatQuantity } from "@shared/lib/format";
import { Page } from "@shared/ui/page/Page";

/**
 * Una factura con sus líneas: el conteo de cada una y las decisiones de la factura entera.
 *
 * Los botones se ofrecen según lo que el usuario puede hacer **y** lo que la factura admite. La
 * API decide igual (y responde el código que corresponda); el botón solo evita ofrecer lo que
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
      setLoadError("Esa factura no existe.");
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
        if (active) setLoadError(caught instanceof ApiError ? caught.message : "No se pudo cargar la factura.");
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
    dispatch(showToast("Conteo guardado.", "success"));
    refresh();
  };

  const decide = async (body: DecisionBody) => {
    if (!deciding) return;
    if (deciding === "approve") {
      await receivingApi.approve(invoiceId, body);
      dispatch(showToast("Discrepancias aprobadas.", "success"));
    } else {
      await receivingApi.rejectDiscrepancy(invoiceId, body);
      dispatch(showToast("Discrepancias rechazadas: las líneas vuelven a contarse.", "success"));
    }
    setDeciding(null);
    refresh();
  };

  /** Asignarse una línea: la API la deja a nombre de quien llama y responde la línea actualizada. */
  const assignToMe = async (line: InvoiceLine) => {
    setBusy(true);
    setActionError(null);
    try {
      await receivingApi.assign(invoiceId, line.id, {});
      dispatch(showToast("Línea asignada.", "success"));
      refresh();
    } catch (caught) {
      setActionError(caught instanceof ApiError ? caught.message : "No se pudo asignar la línea.");
    } finally {
      setBusy(false);
    }
  };

  const close = async () => {
    setActionError(null);
    try {
      await receivingApi.close(invoiceId);
      dispatch(showToast("Factura cerrada.", "success"));
      refresh();
    } catch (caught) {
      setActionError(caught instanceof ApiError ? caught.message : "No se pudo cerrar la factura.");
    }
  };

  const breadcrumbs = [
    { label: "Inicio", href: "#/" },
    { label: "Recepción", href: "#/invoices" },
    { label: invoice?.invoiceNumber ?? "Factura" },
  ];

  if (loadError) {
    return (
      <Page title="Factura" breadcrumbs={breadcrumbs}>
        <ITAlert variant="error" title="No se pudo abrir la factura">{loadError}</ITAlert>
      </Page>
    );
  }
  if (!invoice) {
    return (
      <Page title="Factura" breadcrumbs={breadcrumbs}>
        <ITText muted>Cargando la factura…</ITText>
      </Page>
    );
  }

  const hasDiscrepancy = invoice.lines.some((line) => line.status === "DISCREPANCY");
  const closed = invoice.status === "COMPLETED" || invoice.status === "CANCELLED";

  return (
    <Page
      title={`Factura ${invoice.invoiceNumber}`}
      description={`Recibida el ${invoice.receiptDate} · ${invoice.invoiceType === "NATIONAL" ? "Nacional" : "Internacional"}`}
      breadcrumbs={breadcrumbs}
    >
      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITBadget label={invoice.status} color="secondary" size="sm" />
        <ITText muted className="text-sm">
          {invoice.totalLines} líneas · {invoice.pendingApprovalLines} pendientes de aprobar
        </ITText>
      </ITFlex>

      {actionError && <ITAlert variant="error" title="No se pudo completar la acción">{actionError}</ITAlert>}

      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITButton label="Volver a recepción" variant="outline" onClick={() => navigate("/invoices")} />
        {can(user, "invoice.approve") && hasDiscrepancy && (
          <ITButton label="Aprobar discrepancias" color="primary" onClick={() => setDeciding("approve")} />
        )}
        {can(user, "invoice.reject") && hasDiscrepancy && (
          <ITButton label="Rechazar discrepancias" variant="outline" color="danger" onClick={() => setDeciding("reject")} />
        )}
        {can(user, "invoice.close") && !closed && (
          <ITButton label="Cerrar factura" variant="outline" onClick={() => void close()} />
        )}
      </ITFlex>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] border-collapse text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="py-2 pr-3">Producto</th>
              <th className="py-2 pr-3 text-right">Facturado</th>
              <th className="py-2 pr-3 text-right">Contado</th>
              <th className="py-2 pr-3 text-right">Diferencia</th>
              <th className="py-2 pr-3">Estado</th>
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
                      <ITButton label="Asignarme" size="sm" variant="outline" disabled={busy} onClick={() => void assignToMe(line)} />
                    )}
                    {can(user, "invoice.capture") && isCountable(line.status) && (
                      <ITButton label="Contar" size="sm" variant="outline" onClick={() => setCounting(line)} />
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
        title={deciding === "approve" ? "Aprobar discrepancias" : "Rechazar discrepancias"}
        description={
          deciding === "approve"
            ? "Aceptas la diferencia de cada línea en discrepancia: la mercancía se queda y puede ubicarse."
            : "No aceptas la diferencia: la línea vuelve a pendiente y se cuenta de nuevo."
        }
        confirmLabel={deciding === "approve" ? "Aprobar" : "Rechazar"}
        onClose={() => setDeciding(null)}
        onSubmit={decide}
      />
    </Page>
  );
}
