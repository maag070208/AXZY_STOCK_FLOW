import type {
  AdjustmentRequest,
  KardexParams,
  LocateRequest,
  StockItem,
  StockParams,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad. */
export type StockItemDto = StockItem;

/** Filtros del listado de existencias. */
export type InventoryListParams = StockParams;

/** Filtros del kardex. `from`/`to` son fechas operativas (`YYYY-MM-DD`). */
export type KardexListParams = KardexParams;

/** Cuerpo del ajuste. La idempotencia va en la cabecera `Idempotency-Key`, no aquí. */
export type AdjustmentRequestDto = AdjustmentRequest;

/** Cuerpo de ubicar mercancía. */
export type LocateRequestDto = LocateRequest;

/** Filtros de la bandeja de ajustes pendientes. */
export interface AdjustmentListParams {
  page?: number;
  size?: number;
  productId?: number;
  status?: string;
}
