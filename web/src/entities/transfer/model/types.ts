import type {
  InTransitTransfer,
  PageParams,
  Transfer,
  TransferStatus,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad. */
export type TransferDto = Transfer;
export type InTransitTransferDto = InTransitTransfer;

export type { TransferStatus };

/** Filtros del listado de traslados. */
export interface TransferQueryParams extends PageParams {
  status?: TransferStatus;
  fromWarehouseId?: number;
  toWarehouseId?: number;
}

/**
 * Alta de traslado. El contrato no detalla el cuerpo más allá del ejemplo de
 * respuesta; se declara lo mínimo que la UI necesita enviar y se deja abierto
 * para las líneas, que la Fase 5 cerrará contra el backend.
 */
export interface TransferCreatePayload {
  fromWarehouseId: number;
  toWarehouseId: number;
  lines: Array<{ productId: number; quantity: number }>;
  note?: string | null;
}
