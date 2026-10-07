import { api, get } from "@shared/api";
import type { InTransitTransfer, Page, Transfer, TransferListParams } from "@shared/api";
import type { TransferCreatePayload } from "../model/types";

/**
 * API de traslados entre bodegas (§7 del contrato).
 *
 * La invariante que no se puede romper: `Σ stock_by_location + Σ stock_in_transit`
 * **se conserva en cada transición**. Por eso despachar mueve a tránsito (no
 * resta del total) y recibir lo saca de tránsito. Un traslado nunca recibido
 * aparece como deuda visible en `/transfers/in-transit`, con su responsable.
 */

export const fetchTransfers = (params: TransferListParams = {}): Promise<Page<Transfer>> =>
  get<Page<Transfer>>("/transfers", { params: { size: 50, ...params } });

export const fetchTransfer = (id: number): Promise<Transfer> =>
  get<Transfer>(`/transfers/${id}`);

export const createTransfer = (body: TransferCreatePayload): Promise<Transfer> =>
  api.post<Transfer>("/transfers", body);

export const dispatchTransfer = (
  id: number,
  idempotencyKey?: string
): Promise<Transfer> => api.post<Transfer>(`/transfers/${id}/dispatch`, undefined, { idempotencyKey });

export const receiveTransfer = (
  id: number,
  idempotencyKey?: string
): Promise<Transfer> => api.post<Transfer>(`/transfers/${id}/receive`, undefined, { idempotencyKey });

export const cancelTransfer = (id: number, reason: string): Promise<Transfer> =>
  api.post<Transfer>(`/transfers/${id}/cancel`, { reason });

/** Despachados y no recibidos, con responsable y días transcurridos. */
export const fetchInTransit = (
  params: TransferListParams = {}
): Promise<Page<InTransitTransfer>> =>
  get<Page<InTransitTransfer>>("/transfers/in-transit", { params: { size: 50, ...params } });
