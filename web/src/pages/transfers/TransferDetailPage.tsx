import { ITAlert, ITBadget, ITButton, ITDialog, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate, useParams } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import { can } from "@entities/session";
import { transfersApi, type TransferDetail } from "@entities/transfers";

import { formatQuantity } from "@shared/lib/format";
import { Page } from "@shared/ui/page/Page";
import { FaExchangeAlt } from "react-icons/fa";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

/**
 * A transfer with its lines and the three actions that move goods: dispatch, receive and cancel.
 *
 * Each action moves stock on the server, in a single transaction. The web only decides which button
 * offer based on the status and the permission; the API confirms whether the status is valid (`INVALID_TRANSITION`).
 *
 * Cancelling a **dispatched** transfer returns the goods to their origin: confirmation is requested before
 * to send, because there is no way to know from the screen that the user made a mistake.
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
      setLoadError(i18n.t("transfers.notFound"));
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
        if (active) setLoadError(describeError(caught, "transfers.loadFailed"));
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
      setActionError(describeError(caught, "common.actionFailedDot"));
    } finally {
      setBusy(false);
      setConfirmingCancel(false);
    }
  };

  const breadcrumbs = [
    { label: i18n.t("nav.home"), href: "#/" },
    { label: i18n.t("nav.transfers"), href: "#/transfers" },
    { label: detail?.transfer.code ?? i18n.t("transfers.transfer") },
  ];

  if (loadError) {
    return (
      <Page icon={<FaExchangeAlt size={18} />} title={i18n.t("transfers.transfer")} breadcrumbs={breadcrumbs}>
        <ITAlert variant="error" title={i18n.t("transfers.openFailed")}>{loadError}</ITAlert>
      </Page>
    );
  }
  if (!detail) {
    return (
      <Page title={i18n.t("transfers.transfer")} breadcrumbs={breadcrumbs}>
        <ITText muted>{i18n.t("transfers.loading")}</ITText>
      </Page>
    );
  }

  const { transfer, lines } = detail;
  const inCamino = transfer.status === "DISPATCHED" || transfer.status === "IN_TRANSIT";
  // Docs/api/traslados.md: it is cancelled from DRAFT and from DISPATCHED; from RECEIVED it no longer can be.
  const cancellable = transfer.status === "DRAFT" || transfer.status === "DISPATCHED";

  return (
    <Page
      title={`${i18n.t("transfers.transfer")} ${transfer.code}`}
      description={i18n.t("transfers.routeSummary", { from: transfer.fromWarehouseCode, to: transfer.toWarehouseCode, lines: transfer.lineCount, units: formatQuantity(transfer.totalUnits) })}
      breadcrumbs={breadcrumbs}
    >
      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITBadget label={transfer.status} color="secondary" size="sm" />
        {transfer.dispatchedByName && <ITText muted className="text-sm">Despachó {transfer.dispatchedByName}</ITText>}
        {transfer.receivedByName && <ITText muted className="text-sm">Recibió {transfer.receivedByName}</ITText>}
      </ITFlex>

      {actionError && <ITAlert variant="error" title={i18n.t("common.actionFailedShort")}>{actionError}</ITAlert>}

      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITButton label={i18n.t("transfers.backToTransfers")} variant="outline" onClick={() => navigate("/transfers")} />
        {can(user, "transfer.dispatch") && transfer.status === "DRAFT" && (
          <ITButton label={i18n.t("transfers.dispatch")} color="primary" disabled={busy} onClick={() => void run(transfersApi.dispatch, i18n.t("transfers.dispatched"))} />
        )}
        {can(user, "transfer.receive") && inCamino && (
          <ITButton label={i18n.t("transfers.receive")} color="primary" disabled={busy} onClick={() => void run(transfersApi.receive, i18n.t("transfers.received"))} />
        )}
        {can(user, "transfer.cancel") && cancellable && (
          <ITButton label={i18n.t("transfers.cancelTransfer")} variant="outline" color="danger" disabled={busy} onClick={() => setConfirmingCancel(true)} />
        )}
      </ITFlex>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] border-collapse text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="py-2 pr-3">{i18n.t("common.product")}</th>
              <th className="py-2 pr-3 text-right">{i18n.t("common.quantity")}</th>
              <th className="py-2 pr-3">{i18n.t("transfers.sourceLocation")}</th>
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

      <ITDialog isOpen={confirmingCancel} onClose={() => setConfirmingCancel(false)} title={i18n.t("transfers.cancelTransferTitle")}>
        <ITFlex direction="column" gap={4}>
          <ITText muted className="text-sm">
            {transfer.status === "DISPATCHED"
              ? i18n.t("transfers.cancelHintDispatched", { warehouse: transfer.fromWarehouseCode })
              : i18n.t("transfers.cancelHintNoStock")}
          </ITText>
          <ITFlex justify="end" gap={2}>
            <ITButton label={i18n.t("transfers.noCancel")} variant="outline" disabled={busy} onClick={() => setConfirmingCancel(false)} />
            <ITButton label={i18n.t("transfers.yesCancel")} color="danger" disabled={busy} onClick={() => void run(transfersApi.cancel, i18n.t("transfers.cancelled"))} />
          </ITFlex>
        </ITFlex>
      </ITDialog>
    </Page>
  );
}
