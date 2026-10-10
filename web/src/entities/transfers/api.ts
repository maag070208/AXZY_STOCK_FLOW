import { get, post } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { InTransit, TransferDetail, TransferHeader } from "./model";

export const transfersApi = {
  transfers: (query: PageQuery, filters: { status?: string; warehouseId?: number }): Promise<Page<TransferHeader>> =>
    get<Page<TransferHeader>>("/transfers", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),

  /** The goods in transit. Without pagination: it is a bounded inbox (docs/api/convenciones.md §1.4). */
  inTransit: (): Promise<InTransit[]> => get<InTransit[]>("/transfers/in-transit"),

  detail: (id: number): Promise<TransferDetail> => get<TransferDetail>(`/transfers/${id}`),

  /** The three actions take no body: the stock movement is decided by the server, not by the client. */
  dispatch: (id: number): Promise<TransferDetail> => post<TransferDetail>(`/transfers/${id}/dispatch`),
  receive: (id: number): Promise<TransferDetail> => post<TransferDetail>(`/transfers/${id}/receive`),
  cancel: (id: number): Promise<TransferDetail> => post<TransferDetail>(`/transfers/${id}/cancel`),
};
