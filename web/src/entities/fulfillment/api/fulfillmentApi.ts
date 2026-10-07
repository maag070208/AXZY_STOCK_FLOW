import { api, get } from "@shared/api";
import type {
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

export const fetchOrder = (id: number): Promise<OrderDetail> => get<OrderDetail>(`/orders/${id}`);

export const createOrder = (body: Record<string, unknown>): Promise<OrderSummary> =>
  api.post<OrderSummary>("/orders", body);

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
): Promise<void> => api.requestVoid("post", `/orders/${orderId}/shortage`, body);
