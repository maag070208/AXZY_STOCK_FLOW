import { get, post, put } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { AssignBody, ExportAttempt, OrderDetail, OrderHeader, PackBody, ShortageBody } from "./model";

export const fulfillmentApi = {
  orders: (query: PageQuery, filters: { status?: string }): Promise<Page<OrderHeader>> =>
    get<Page<OrderHeader>>("/orders", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),

  detail: (id: number): Promise<OrderDetail> => get<OrderDetail>(`/orders/${id}`),

  /** Deducts from inventory what was packed and responds with the updated order. */
  pack: (orderId: number, lineId: number, body: PackBody): Promise<OrderDetail> =>
    post<OrderDetail>(`/orders/${orderId}/lines/${lineId}/package`, body),

  /** A shortage is an adjustment with a fulfillment origin: the reason is mandatory. */
  shortage: (orderId: number, body: ShortageBody): Promise<OrderDetail> =>
    post<OrderDetail>(`/orders/${orderId}/shortage`, body),

  assign: (orderId: number, lineId: number, body: AssignBody): Promise<OrderDetail> =>
    put<OrderDetail>(`/orders/${orderId}/lines/${lineId}/assign`, body),

  /** Deducts `quantity - fulfilled` from each line and triggers the export to the ERP. */
  confirm: (orderId: number): Promise<OrderDetail> => post<OrderDetail>(`/orders/${orderId}/confirm`),

  /** Manual retry of the export. It responds with the attempt, not the order: the order must be requested again. */
  exportOrder: (orderId: number): Promise<ExportAttempt> => post<ExportAttempt>(`/orders/${orderId}/export`),
};
