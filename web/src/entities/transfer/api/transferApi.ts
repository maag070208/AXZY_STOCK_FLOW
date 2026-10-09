import { api, get } from "@shared/api";
import type {
  InTransitTransfer,
  Page,
  Transfer,
  TransferDetail,
  TransferListParams,
} from "@shared/api";
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

export const fetchTransfer = (id: number): Promise<TransferDetail> =>
  get<TransferDetail>(`/transfers/${id}`);

export const createTransfer = (body: TransferCreatePayload): Promise<TransferDetail> =>
  api.post<TransferDetail>("/transfers", body);

export const dispatchTransfer = (
  id: number,
  idempotencyKey?: string
): Promise<TransferDetail> =>
  api.post<TransferDetail>(`/transfers/${id}/dispatch`, undefined, { idempotencyKey });

export const receiveTransfer = (
  id: number,
  idempotencyKey?: string
): Promise<TransferDetail> =>
  api.post<TransferDetail>(`/transfers/${id}/receive`, undefined, { idempotencyKey });

/**
 * Cancela un traslado.
 *
 * **Sin `reason`.** El contrato no pide motivo para cancelar y la ruta no lo lee:
 * mandarlo haría creer que el servidor lo guarda, y la interfaz acabaría pidiendo
 * al usuario un dato que se descarta.
 */
export const cancelTransfer = (id: number): Promise<TransferDetail> =>
  api.post<TransferDetail>(`/transfers/${id}/cancel`, undefined);

/**
 * Despachados y no recibidos, con responsable y días transcurridos.
 *
 * **Devuelve un ARRAY, no un sobre de paginación.** El contrato no lo pagina: es
 * una lista de deudas abiertas y su número está acotado por la operación real. Si
 * el cliente esperara un `Page`, `items` sería `undefined` y la pantalla saldría
 * vacía **sin fallar** — el mismo defecto que tenía el informe de reconciliación.
 */
export const fetchInTransit = (
  params: TransferListParams = {}
): Promise<InTransitTransfer[]> =>
  // Se pasa el filtro explícito y no el objeto entero: `TransferListParams` es un
  // tipo con campos conocidos y `QueryParams` espera un índice de cadena, así que
  // pasarlo tal cual no compila. Copiar el campo evita además mandar de más.
  get<InTransitTransfer[]>("/transfers/in-transit", {
    params: params.warehouseId != null ? { warehouseId: params.warehouseId } : {},
  });
