import { ITAlert, ITBadget, ITButton, ITDialog, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate, useParams } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import { can } from "@entities/session";
import { transfersApi, type TransferDetail } from "@entities/transfers";
import { ApiError } from "@shared/api/types";
import { formatQuantity } from "@shared/lib/format";
import { Page } from "@shared/ui/page/Page";

/**
 * Un traslado con sus líneas y las tres acciones que mueven mercancía: despachar, recibir y cancelar.
 *
 * Cada acción mueve stock en el servidor, en una sola transacción. La web solo decide qué botón
 * ofrecer según el estado y el permiso; el estado válido lo confirma la API (`INVALID_TRANSITION`).
 *
 * Cancelar un traslado **despachado** devuelve la mercancía a su origen: se pide confirmación antes
 * de enviar, porque no hay forma de saber desde la pantalla que el usuario se equivocó.
 */
export default function TransferDetailPage() {
  const { id } = useParams();
  const transferId = Number(id);
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);

  const [detail, setDetail] = useState<TransferDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmingCancel, setConfirmingCancel] = useState(false);

  useEffect(() => {
    if (!Number.isInteger(transferId) || transferId <= 0) {
      setLoadError("Ese traslado no existe.");
      return;
    }
    let active = true;
    transfersApi
      .detail(transferId)
      .then((data) => {
        if (!active) return;
        setDetail(data);
        setLoadError(null);
      })
      .catch((caught: unknown) => {
        if (active) setLoadError(caught instanceof ApiError ? caught.message : "No se pudo cargar el traslado.");
      });
    return () => {
      active = false;
    };
  }, [transferId]);

  const run = async (action: (id: number) => Promise<TransferDetail>, done: string) => {
    setBusy(true);
    setActionError(null);
    try {
      const updated = await action(transferId);
      setDetail(updated);
      dispatch(showToast(done, "success"));
    } catch (caught) {
      setActionError(caught instanceof ApiError ? caught.message : "No se pudo completar la acción.");
    } finally {
      setBusy(false);
      setConfirmingCancel(false);
    }
  };

  const breadcrumbs = [
    { label: "Inicio", href: "#/" },
    { label: "Traslados", href: "#/transfers" },
    { label: detail?.transfer.code ?? "Traslado" },
  ];

  if (loadError) {
    return (
      <Page title="Traslado" breadcrumbs={breadcrumbs}>
        <ITAlert variant="error" title="No se pudo abrir el traslado">{loadError}</ITAlert>
      </Page>
    );
  }
  if (!detail) {
    return (
      <Page title="Traslado" breadcrumbs={breadcrumbs}>
        <ITText muted>Cargando el traslado…</ITText>
      </Page>
    );
  }

  const { transfer, lines } = detail;
  const inCamino = transfer.status === "DISPATCHED" || transfer.status === "IN_TRANSIT";
  // Docs/api/traslados.md: se cancela desde DRAFT y desde DISPATCHED; desde RECEIVED ya no.
  const cancellable = transfer.status === "DRAFT" || transfer.status === "DISPATCHED";

  return (
    <Page
      title={`Traslado ${transfer.code}`}
      description={`${transfer.fromWarehouseCode} → ${transfer.toWarehouseCode} · ${transfer.lineCount} líneas · ${formatQuantity(transfer.totalUnits)} unidades`}
      breadcrumbs={breadcrumbs}
    >
      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITBadget label={transfer.status} color="secondary" size="sm" />
        {transfer.dispatchedByName && <ITText muted className="text-sm">Despachó {transfer.dispatchedByName}</ITText>}
        {transfer.receivedByName && <ITText muted className="text-sm">Recibió {transfer.receivedByName}</ITText>}
      </ITFlex>

      {actionError && <ITAlert variant="error" title="No se pudo completar la acción">{actionError}</ITAlert>}

      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITButton label="Volver a traslados" variant="outline" onClick={() => navigate("/transfers")} />
        {can(user, "transfer.dispatch") && transfer.status === "DRAFT" && (
          <ITButton label="Despachar" color="primary" disabled={busy} onClick={() => void run(transfersApi.dispatch, "Traslado despachado.")} />
        )}
        {can(user, "transfer.receive") && inCamino && (
          <ITButton label="Recibir" color="primary" disabled={busy} onClick={() => void run(transfersApi.receive, "Traslado recibido.")} />
        )}
        {can(user, "transfer.cancel") && cancellable && (
          <ITButton label="Cancelar traslado" variant="outline" color="danger" disabled={busy} onClick={() => setConfirmingCancel(true)} />
        )}
      </ITFlex>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-collapse text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="py-2 pr-3">Producto</th>
              <th className="py-2 pr-3 text-right">Cantidad</th>
              <th className="py-2 pr-3">Ubicación de origen</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => (
              <tr key={line.id} className="border-b">
                <td className="py-2 pr-3">
                  <div className="font-medium">{line.productCode}</div>
                  <div className="text-xs text-slate-500">{line.productName}</div>
                </td>
                <td className="py-2 pr-3 text-right">{formatQuantity(line.quantity)}</td>
                <td className="py-2 pr-3">{line.fromLocationDisplayName ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ITDialog isOpen={confirmingCancel} onClose={() => setConfirmingCancel(false)} title="Cancelar el traslado">
        <ITFlex direction="column" gap={4}>
          <ITText muted className="text-sm">
            {transfer.status === "DISPATCHED"
              ? `La mercancía vuelve a su bodega de origen (${transfer.fromWarehouseCode}). Esta acción queda en la bitácora.`
              : "El traslado se cancela. No se ha movido mercancía."}
          </ITText>
          <ITFlex justify="end" gap={2}>
            <ITButton label="No cancelar" variant="outline" disabled={busy} onClick={() => setConfirmingCancel(false)} />
            <ITButton label="Sí, cancelar" color="danger" disabled={busy} onClick={() => void run(transfersApi.cancel, "Traslado cancelado.")} />
          </ITFlex>
        </ITFlex>
      </ITDialog>
    </Page>
  );
}
