import type {
  AdjustmentReason,
  OrderDetail,
  OrderExportResult,
  OrderExportStatus,
  OrderLine,
  OrderListParams,
  OrderStatus,
  OrderSummary,
  PackageLineRequest,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad. */
export type OrderSummaryDto = OrderSummary;
export type OrderDetailDto = OrderDetail;
export type OrderLineDto = OrderLine;
export type OrderExportResultDto = OrderExportResult;

export type { AdjustmentReason, OrderExportStatus, OrderListParams, OrderStatus };

export type PackageLinePayload = PackageLineRequest;

/** Faltante de surtido: alimenta la regla única de ajuste. */
export interface OrderShortagePayload {
  lineId: number;
  quantity: number;
  reason: AdjustmentReason;
  note?: string | null;
}
