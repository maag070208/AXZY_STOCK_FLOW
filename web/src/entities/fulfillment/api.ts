import { get, post, put } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { AssignBody, ExportAttempt, OrderDetail, OrderHeader, PackBody, ShortageBody } from "./model";

export const fulfillmentApi = {
  orders: (query: PageQuery, filters: { status?: string }): Promise<Page<OrderHeader>> =>
    get<Page<OrderHeader>>("/orders", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),

  detail: (id: number): Promise<OrderDetail> => get<OrderDetail>(`/orders/${id}`),

  /** Descuenta del inventario lo empacado y responde la orden actualizada. */
  pack: (orderId: number, lineId: number, body: PackBody): Promise<OrderDetail> =>
    post<OrderDetail>(`/orders/${orderId}/lines/${lineId}/package`, body),

  /** Un faltante es un ajuste con origen de surtido: el motivo es obligatorio. */
  shortage: (orderId: number, body: ShortageBody): Promise<OrderDetail> =>
    post<OrderDetail>(`/orders/${orderId}/shortage`, body),

  assign: (orderId: number, lineId: number, body: AssignBody): Promise<OrderDetail> =>
    put<OrderDetail>(`/orders/${orderId}/lines/${lineId}/assign`, body),

  /** Descuenta `cantidad - surtido` de cada línea y dispara la exportación al ERP. */
  confirm: (orderId: number): Promise<OrderDetail> => post<OrderDetail>(`/orders/${orderId}/confirm`),

  /** Reintento manual de la exportación. Responde el intento, no la orden: hay que volver a pedirla. */
  exportOrder: (orderId: number): Promise<ExportAttempt> => post<ExportAttempt>(`/orders/${orderId}/export`),
};
