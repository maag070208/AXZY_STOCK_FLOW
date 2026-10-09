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
 * Alta de traslado (§7).
 *
 * **Las líneas van por `productCode` y la cantidad es CADENA decimal**, que es lo
 * que acepta la API. Estaba declarado con `productId` y `quantity: number`, y
 * ninguna de las dos cosas existe en la ruta: el servidor habría devuelto `422`
 * por producto inexistente y por cantidad ilegible.
 *
 * Se usa el código y no el id porque **el código es lo que el operador tiene en
 * la mano** —el de la etiqueta del estante— y obligar a la UI a resolver antes un
 * id añadiría una petición por línea.
 */
export interface TransferCreatePayload {
  fromWarehouseId: number;
  toWarehouseId: number;
  note?: string | null;
  lines: Array<{ productCode: string; quantity: string; fromLocationId?: number | null }>;
}

/** Filtros del listado de tránsito. */
export interface InTransitParams {
  warehouseId?: number;
}
