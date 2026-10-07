/** Entidad `reception`: facturas, captura, ubicación y aprobación (§5 del contrato). */
export {
  approveInvoice,
  approveInvoiceLine,
  captureLine,
  closeInvoice,
  createInvoice,
  fetchInvoice,
  fetchInvoices,
  fetchPendingApproval,
  rejectDiscrepancy,
} from "./api/receptionApi";
export type {
  ApproveInvoicePayload,
  CaptureLinePayload,
  InvoiceDetailDto,
  InvoiceLineDto,
  InvoiceQueryParams,
  InvoiceSummaryDto,
  RejectDiscrepancyPayload,
} from "./model/types";
export type {
  InvoiceLineStatus,
  InvoiceStatus,
  InvoiceType,
} from "@shared/api";
