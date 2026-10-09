import type {
  OrderDetail,
  OrderExportResult,
  OrderLine,
  OrderListParams,
  OrderShortagePayload as OrderShortagePayloadShared,
  OrderStatus,
  OrderSummary,
  PackageLinePayload as PackageLinePayloadShared,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad (§6). */
export type OrderSummaryDto = OrderSummary;
export type OrderDetailDto = OrderDetail;
export type OrderLineDto = OrderLine;
export type OrderExportResultDto = OrderExportResult;

export type { OrderListParams, OrderStatus };

/**
 * El payload del empaque y el del faltante.
 *
 * **Se reexportan los tipos de `@shared/api` en vez de declarar unos propios.**
 * Aquí había una copia —con `quantity: number` y `reason` obligatorio— que no
 * coincidía con la API: la cantidad se manda como cadena decimal (§1.7) y el
 * motivo es opcional porque el servidor le pone `SHORTAGE` por defecto.
 *
 * Dos declaraciones del mismo contrato se separan en cuanto una cambia, y este
 * archivo es exactamente donde eso pasó.
 */
export type PackageLinePayload = PackageLinePayloadShared;
export type OrderShortagePayload = OrderShortagePayloadShared;
