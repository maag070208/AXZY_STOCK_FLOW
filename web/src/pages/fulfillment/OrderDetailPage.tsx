import { ITAlert, ITBadget, ITButton, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate, useParams } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import PackLineDialog from "@features/fulfillment/PackLineDialog";
import ShortageDialog from "@features/fulfillment/ShortageDialog";
import { can } from "@entities/session";
import { fulfillmentApi, type OrderDetail, type OrderLine } from "@entities/fulfillment";

import { formatQuantity } from "@shared/lib/format";
import { Page } from "@shared/ui/page/Page";
import { FaTruck } from "react-icons/fa";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

/**
 * Una orden con sus líneas y las acciones del surtido: asignarse una línea, empacar, registrar un
 * faltante, confirmar la orden y reintentar su exportación.
 *
 * Cada acción mueve inventario o le escribe algo al ERP en el servidor. La web ofrece el botón según
 * el permiso y el estado, y tras cada acción vuelve a pedir la orden: lo que se muestra es lo que el
 * servidor tiene, no lo que la web supone que pasó.
 */
export default function OrderDetailPage() {
  const { id } = useParams();
  const orderId = Number(id);
  const navigate = useNavigate();
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);

  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);
  const [packing, setPacking] = useState<OrderLine | null>(null);
  const [shorting, setShorting] = useState<OrderLine | null>(null);

  useEffect(() => {
    if (!Number.isInteger(orderId) || orderId <= 0) {
      setLoadError(i18n.t("fulfillment.notFound"));
      return;
    }
    let active = true;
    fulfillmentApi
      .detail(orderId)
      .then((data) => {
        if (!active) return;
        setOrder(data);
        setLoadError(null);
      })
      .catch((caught: unknown) => {
        if (active) setLoadError(describeError(caught, "fulfillment.loadFailed"));
      });
    return () => {
      active = false;
    };
  }, [orderId, version]);

  const refresh = () => setVersion((current) => current + 1);

  /**
   * Una acción: si la API la acepta, se avisa y se vuelve a pedir la orden. Si la rechaza, el error
   * se relanza para que el diálogo que la pidió lo muestre; las acciones sin diálogo lo muestran aquí.
   */
  const run = async (action: () => Promise<unknown>, done: string, { rethrow = false } = {}) => {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      dispatch(showToast(done, "success"));
      setPacking(null);
      setShorting(null);
      refresh();
    } catch (caught) {
      if (rethrow) throw caught;
      setActionError(describeError(caught, "common.actionFailedDot"));
    } finally {
      setBusy(false);
    }
  };

  const breadcrumbs = [
    { label: i18n.t("nav.home"), href: "#/" },
    { label: i18n.t("nav.fulfillment"), href: "#/orders" },
    { label: order?.orderNumber ?? i18n.t("fulfillment.order") },
  ];

  if (loadError) {
    return (
      <Page icon={<FaTruck size={18} />} title={i18n.t("fulfillment.order")} breadcrumbs={breadcrumbs}>
        <ITAlert variant="error" title={i18n.t("fulfillment.openFailed")}>{loadError}</ITAlert>
      </Page>
    );
  }
  if (!order) {
    return (
      <Page title={i18n.t("fulfillment.order")} breadcrumbs={breadcrumbs}>
        <ITText muted>{i18n.t("fulfillment.loadingOrder")}</ITText>
      </Page>
    );
  }

  const pendingOf = (line: OrderLine) => Number(line.quantity) - Number(line.suppliedQuantity);
  const exportDone = order.exportStatus === "SENT";

  return (
    <Page
      title={`${i18n.t("fulfillment.order")} ${order.orderNumber}`}
      description={`${i18n.t("fulfillment.packedSummary", { packed: order.packagedLines, total: order.totalLines })}${order.converted ? i18n.t("fulfillment.confirmedSuffix") : ""}`}
      breadcrumbs={breadcrumbs}
    >
      <ITFlex gap={2} wrap="wrap" className="mb-4">
        <ITBadget label={order.status} color="secondary" size="sm" />
        {order.exportStatus && (
          <ITText muted className="text-sm">
            Exportación: {order.exportStatus} · {order.exportAttempts} intentos
          </ITText>
        )}
      </ITFlex>
      {order.exportLastError && (
        <ITAlert variant="warning" title={i18n.t("fulfillment.lastExportFailed")}>{order.exportLastError}</ITAlert>
      )}

      {actionError && <ITAlert variant="error" title={i18n.t("common.actionFailedShort")}>{actionError}</ITAlert>}

      <ITFlex gap={2} wrap="wrap" className="my-4">
        <ITButton label={i18n.t("fulfillment.backToFulfillment")} variant="outline" onClick={() => navigate("/orders")} />
        {can(user, "order.confirm") && !order.converted && (
          <ITButton label={i18n.t("fulfillment.confirmFulfillment")} color="primary" disabled={busy} onClick={() => void run(() => fulfillmentApi.confirm(orderId), i18n.t("fulfillment.orderConfirmed"))} />
        )}
        {can(user, "order.convert") && order.converted && !exportDone && (
          <ITButton
            label={order.exportAttempts > 0 ? i18n.t("fulfillment.retryExport") : i18n.t("fulfillment.exportToErp")}
            variant="outline"
            disabled={busy}
            onClick={() => void run(() => fulfillmentApi.exportOrder(orderId), i18n.t("fulfillment.exportAttemptRecorded"))}
          />
        )}
      </ITFlex>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] border-collapse text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="py-2 pr-3">{i18n.t("common.product")}</th>
              <th className="py-2 pr-3 text-right">{i18n.t("fulfillment.ordered")}</th>
              <th className="py-2 pr-3 text-right">{i18n.t("nav.fulfillment")}</th>
              <th className="py-2 pr-3 text-right">{i18n.t("fulfillment.pending")}</th>
              <th className="py-2 pr-3">{i18n.t("fulfillment.assignedTo")}</th>
              <th className="py-2 pr-3">{i18n.t("common.status")}</th>
              <th className="py-2 pr-3" />
            </tr>
          </thead>
          <tbody>
            {order.lines.map((line) => {
              const open = !line.supplied && pendingOf(line) > 0;
              return (
                <tr key={line.id} className="border-b">
                  <td className="py-2 pr-3">
                    <div className="font-medium">{line.productCode}</div>
                    <div className="text-xs text-slate-500">{line.productName}</div>
                  </td>
                  <td className="py-2 pr-3 text-right">{formatQuantity(line.quantity)}</td>
                  <td className="py-2 pr-3 text-right">{formatQuantity(line.suppliedQuantity)}</td>
                  <td className="py-2 pr-3 text-right">{formatQuantity(String(pendingOf(line)))}</td>
                  <td className="py-2 pr-3">{line.assignedUserName ?? "—"}</td>
                  <td className="py-2 pr-3">
                    <ITBadget label={line.status} color="secondary" size="sm" />
                  </td>
                  <td className="py-2 pr-3">
                    <ITFlex gap={2} wrap="wrap" justify="end">
                      {can(user, "order.assign") && open && (line.status === "PENDING" || line.status === "ASSIGNED") && (
                        <ITButton label={i18n.t("receiving.assignToMe")} size="sm" variant="outline" disabled={busy} onClick={() => void run(() => fulfillmentApi.assign(orderId, line.id, {}), i18n.t("receiving.lineAssigned"))} />
                      )}
                      {can(user, "order.package") && open && (
                        <ITButton label={i18n.t("fulfillment.pack")} size="sm" variant="outline" disabled={busy} onClick={() => setPacking(line)} />
                      )}
                      {can(user, "order.package") && can(user, "inventory.adjust_shortage") && open && (
                        <ITButton label={i18n.t("reasons.shortage")} size="sm" variant="outline" color="danger" disabled={busy} onClick={() => setShorting(line)} />
                      )}
                    </ITFlex>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <PackLineDialog
        open={packing !== null}
        line={packing}
        onClose={() => setPacking(null)}
        onSubmit={(quantity) =>
          run(() => fulfillmentApi.pack(orderId, packing!.id, { quantity }), i18n.t("fulfillment.packRecorded"), { rethrow: true })
        }
      />

      <ShortageDialog
        open={shorting !== null}
        line={shorting}
        onClose={() => setShorting(null)}
        onSubmit={(body) =>
          run(() => fulfillmentApi.shortage(orderId, { ...body, lineId: shorting!.id }), i18n.t("fulfillment.shortageRecorded"), { rethrow: true })
        }
      />
    </Page>
  );
}
