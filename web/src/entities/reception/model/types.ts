import type {
  AdjustmentReason,
  CaptureLineRequest,
  InvoiceCreateRequest,
  InvoiceDetail,
  InvoiceLine,
  InvoiceListParams,
  InvoiceStatus,
  InvoiceSummary,
  InvoiceType,
} from "@shared/api";

/** Alias de los DTOs del contrato que expone esta entidad. */
export type InvoiceSummaryDto = InvoiceSummary;
export type InvoiceDetailDto = InvoiceDetail;
export type InvoiceLineDto = InvoiceLine;

export type {
  AdjustmentReason,
  InvoiceCreateRequest,
  InvoiceStatus,
  InvoiceType,
};

/** Filtros del listado de facturas: sobre de paginación + `status`/`from`/`to`. */
export type InvoiceQueryParams = InvoiceListParams;

export type CaptureLinePayload = CaptureLineRequest;

export interface ApproveInvoicePayload {
  adjustmentReason: AdjustmentReason;
  note?: string | null;
}

export interface RejectDiscrepancyPayload {
  /** Motivo obligatorio: rechazar una diferencia sin explicación no es auditable. */
  reason: string;
  note?: string | null;
}
