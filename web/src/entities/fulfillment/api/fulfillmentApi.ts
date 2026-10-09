import { api, get } from "@shared/api";
import type {
  OrderCreatePayload,
  OrderDetail,
  OrderExportResult,
  OrderListParams,
  OrderSummary,
  Page,
} from "@shared/api";
import type { OrderShortagePayload, PackageLinePayload } from "../model/types";

/**
 * API de surtido (§6 del contrato).
 *
 * El flujo que hay que respetar: empaquetar descuenta stock y escribe
 * `FULFILLMENT_OUT`; **confirmar** descuenta las líneas pendientes y dispara la
 * exportación al ERP; exportar es un **reintento manual idempotente** (la clave
 * es el `orderNumber` convertido, así que no puede duplicar la orden).
 */

export const fetchOrders = (params: OrderListParams = {}): Promise<Page<OrderSummary>> =>
  get<Page<OrderSummary>>("/orders", { params: { size: 50, ...params } });

/** Listado de órdenes con el filtro de estado, para la pantalla de surtido. */
export const fetchOrdersByStatus = (
  status?: string,
  page = 0,
  size = 50
): Promise<Page<OrderSummary>> =>
  get<Page<OrderSummary>>("/orders", {
    params: status ? { status, page, size } : { page, size },
  });

export const fetchOrder = (id: number): Promise<OrderDetail> => get<OrderDetail>(`/orders/${id}`);

/**
 * Crea una orden.
 *
 * **Devuelve el DETALLE, con sus líneas.** El backend responde `201` con la orden
 * completa porque la pantalla necesita los `id` de línea para poder empacar, y
 * pedirlos después sería una segunda petición inmediata. Estaba declarado como
 * `OrderSummary` —sin líneas— así que el cliente habría visto `lines: undefined`.
 */
export const createOrder = (body: OrderCreatePayload): Promise<OrderDetail> =>
  api.post<OrderDetail>("/orders", body);

/**
 * Empaqueta una línea. Si no alcanza el stock responde
 * `422 INSUFFICIENT_STOCK` con `details.available`, y el faltante se resuelve con
 * un **ajuste** de origen `FULFILLMENT_SHORTAGE`, no con un flujo aparte.
 */
export const packageOrderLine = (
  orderId: number,
  lineId: number,
  body: PackageLinePayload
): Promise<OrderDetail> =>
  api.post<OrderDetail>(`/orders/${orderId}/lines/${lineId}/package`, body, {
    idempotencyKey: body.idempotencyKey,
  });

/** `409 ORDER_ALREADY_CONFIRMED` si ya estaba confirmada. */
export const confirmOrder = (orderId: number, idempotencyKey?: string): Promise<OrderDetail> =>
  api.post<OrderDetail>(`/orders/${orderId}/confirm`, undefined, { idempotencyKey });

/** Reintento manual de exportación. `409 ORDER_NOT_CONVERTED` si aún no se confirmó. */
export const exportOrder = (orderId: number): Promise<OrderExportResult> =>
  api.post<OrderExportResult>(`/orders/${orderId}/export`);

/** Faltante durante el surtido: delega en la regla única de ajuste. */
export const registerOrderShortage = (
  orderId: number,
  body: OrderShortagePayload
): Promise<OrderDetail> =>
  // Devuelve la orden actualizada y no `void`: el `422 INSUFFICIENT_STOCK` ya se
  // resolvió con el ajuste, así que la pantalla tiene que repintar la línea con su
  // `suppliedQuantity` nuevo. Con `void` habría que recargar aparte.
  api.post<OrderDetail>(`/orders/${orderId}/shortage`, body);
