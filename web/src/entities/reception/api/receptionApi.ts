import { api, get } from "@shared/api";
import type {
  InvoiceCreateRequest,
  InvoiceDetail,
  InvoiceSummary,
  Page,
} from "@shared/api";
import type {
  ApproveInvoicePayload,
  CaptureLinePayload,
  InvoiceQueryParams,
  RejectDiscrepancyPayload,
} from "../model/types";

/**
 * API de recepción (§5 del contrato).
 *
 * Dos reglas del negocio que se reflejan en las firmas:
 * - La aprobación es **por factura, no por línea** (decisión R-86): existe
 *   `approveInvoice(id)` y también `approveInvoiceLine(id, lineId)` para el
 *   endpoint por línea del contrato, pero la UI debe ofrecer la de factura.
 * - `difference` la calcula el servidor. Aquí no se recalcula nunca.
 */

export const fetchInvoices = (params: InvoiceQueryParams = {}): Promise<Page<InvoiceSummary>> =>
  get<Page<InvoiceSummary>>("/invoices", { params: { size: 50, ...params } });

/** Incluye las líneas con su estado, cantidades física/documento y `difference`. */
export const fetchInvoice = (id: number): Promise<InvoiceDetail> =>
  get<InvoiceDetail>(`/invoices/${id}`);

export const createInvoice = (body: InvoiceCreateRequest): Promise<InvoiceSummary> =>
  api.post<InvoiceSummary>("/invoices", body);

/** Captura física. Decide `CAPTURED` si coincide o `DISCREPANCY` si no. */
export const captureLine = (
  invoiceId: number,
  lineId: number,
  body: CaptureLinePayload
): Promise<InvoiceDetail> =>
  api.post<InvoiceDetail>(`/invoices/${invoiceId}/lines/${lineId}/capture`, body, {
    idempotencyKey: body.idempotencyKey,
  });

/**
 * Aprobación **por factura**: opera sobre todas las líneas pendientes. Responde
 * `422 SEGREGATION_OF_DUTIES` si quien aprueba fue quien capturó.
 */
export const approveInvoice = (
  invoiceId: number,
  body: ApproveInvoicePayload
): Promise<InvoiceDetail> =>
  api.post<InvoiceDetail>(`/invoices/${invoiceId}/approve`, body);

/** Aprobación por línea. El contrato la expone; la UI preferente es la de factura. */
export const approveInvoiceLine = (
  invoiceId: number,
  lineId: number,
  body: ApproveInvoicePayload
): Promise<InvoiceDetail> =>
  api.post<InvoiceDetail>(`/invoices/${invoiceId}/lines/${lineId}/approve`, body);

export const rejectDiscrepancy = (
  invoiceId: number,
  body: RejectDiscrepancyPayload
): Promise<InvoiceDetail> =>
  api.post<InvoiceDetail>(`/invoices/${invoiceId}/reject-discrepancy`, body);

/** `409 HAS_PENDING_LINES` con `details.pendingLines` si quedan líneas. */
export const closeInvoice = (invoiceId: number): Promise<InvoiceDetail> =>
  api.post<InvoiceDetail>(`/invoices/${invoiceId}/close`);

/** Bandeja de pendientes de aprobación: pantalla **nueva** respecto al legacy. */
export const fetchPendingApproval = (
  params: InvoiceQueryParams = {}
): Promise<Page<InvoiceSummary>> =>
  get<Page<InvoiceSummary>>("/invoices/pending-approval", {
    params: { size: 50, ...params },
  });
