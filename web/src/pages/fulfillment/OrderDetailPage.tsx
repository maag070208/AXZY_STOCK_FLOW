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
import { ApiError } from "@shared/api/types";
import { formatQuantity } from "@shared/lib/format";
import { Page } from "@shared/ui/page/Page";

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
      setLoadError("Esa orden no existe.");
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
        if (active) setLoadError(caught instanceof ApiError ? caught.message : "No se pudo cargar la orden.");
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
      setActionError(caught instanceof ApiError ? caught.message : "No se pudo completar la acción.");
    } finally {
      setBusy(false);
    }
  };

  const breadcrumbs = [
    { label: "Inicio", href: "#/" },
    { label: "Surtido", href: "#/orders" },
    { label: order?.orderNumber ?? "Orden" },
  ];

  if (loadError) {
    return (
      <Page title="Orden" breadcrumbs={breadcrumbs}>
        <ITAlert variant="error" title="No se pudo abrir la orden">{loadError}</ITAlert>
      </Page>
    );
  }
  if (!order) {
    return (
      <Page title="Orden" breadcrumbs={breadcrumbs}>
        <ITText muted>Cargando la orden…</ITText>
      </Page>
    );
  }

  const pendingOf = (line: OrderLine) => Number(line.quantity) - Number(line.suppliedQuantity);
  const exportDone = order.exportStatus === "SENT";

  return (
    <Page
      title={`Orden ${order.orderNumber}`}
      description={`${order.packagedLines} de ${order.totalLines} líneas empacadas${order.converted ? " · confirmada" : ""}`}
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
        <ITAlert variant="warning" title="La última exportación falló">{order.exportLastError}</ITAlert>
      )}

      {actionError && <ITAlert variant="error" title="No se pudo completar la acción">{actionError}</ITAlert>}

      <ITFlex gap={2} wrap="wrap" className="my-4">
        <ITButton label="Volver a surtido" variant="outline" onClick={() => navigate("/orders")} />
        {can(user, "order.confirm") && !order.converted && (
          <ITButton label="Confirmar surtido" color="primary" disabled={busy} onClick={() => void run(() => fulfillmentApi.confirm(orderId), "Orden confirmada.")} />
        )}
        {can(user, "order.convert") && order.converted && !exportDone && (
          <ITButton
            label={order.exportAttempts > 0 ? "Reintentar exportación" : "Exportar al ERP"}
            variant="outline"
            disabled={busy}
            onClick={() => void run(() => fulfillmentApi.exportOrder(orderId), "Intento de exportación registrado.")}
          />
        )}
      </ITFlex>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] border-collapse text-sm">
          <thead>
            <tr className="border-b text-left">
              <th className="py-2 pr-3">Producto</th>
              <th className="py-2 pr-3 text-right">Pedido</th>
              <th className="py-2 pr-3 text-right">Surtido</th>
              <th className="py-2 pr-3 text-right">Pendiente</th>
              <th className="py-2 pr-3">Asignado a</th>
              <th className="py-2 pr-3">Estado</th>
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
                        <ITButton label="Asignarme" size="sm" variant="outline" disabled={busy} onClick={() => void run(() => fulfillmentApi.assign(orderId, line.id, {}), "Línea asignada.")} />
                      )}
                      {can(user, "order.package") && open && (
                        <ITButton label="Empacar" size="sm" variant="outline" disabled={busy} onClick={() => setPacking(line)} />
                      )}
                      {can(user, "order.package") && can(user, "inventory.adjust_shortage") && open && (
                        <ITButton label="Faltante" size="sm" variant="outline" color="danger" disabled={busy} onClick={() => setShorting(line)} />
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
          run(() => fulfillmentApi.pack(orderId, packing!.id, { quantity }), "Empaque registrado.", { rethrow: true })
        }
      />

      <ShortageDialog
        open={shorting !== null}
        line={shorting}
        onClose={() => setShorting(null)}
        onSubmit={(body) =>
          run(() => fulfillmentApi.shortage(orderId, { ...body, lineId: shorting!.id }), "Faltante registrado.", { rethrow: true })
        }
      />
    </Page>
  );
}
