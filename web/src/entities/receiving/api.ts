import { get, post, put } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type {
  CaptureBody,
  DecisionBody,
  InvoiceDetail,
  InvoiceHeader,
  InvoiceLine,
  NewInvoice,
  PendingApproval,
} from "./model";

export const receivingApi = {
  invoices: (query: PageQuery, filters: { status?: string }): Promise<Page<InvoiceHeader>> =>
    get<Page<InvoiceHeader>>("/invoices", { params: { ...toQueryParams(query), ...compactParams({ ...filters }) } }),

  /** La bandeja no usa el sobre de paginación: es `{ items: [...] }` (docs/api/recepcion.md). */
  pendingApproval: (): Promise<{ items: PendingApproval[] }> =>
    get<{ items: PendingApproval[] }>("/invoices/pending-approval"),

  /** Alta. La API responde la referencia (201), no la factura: el detalle se pide aparte. */
  create: (body: NewInvoice): Promise<{ id: number; invoiceNumber: string }> =>
    post<{ id: number; invoiceNumber: string }>("/invoices", body),

  detail: (id: number): Promise<InvoiceDetail> => get<InvoiceDetail>(`/invoices/${id}`),

  /** Responde la línea, no la factura. */
  capture: (invoiceId: number, lineId: number, body: CaptureBody): Promise<InvoiceLine> =>
    post<InvoiceLine>(`/invoices/${invoiceId}/lines/${lineId}/capture`, body),

  /** Sin `userId` la línea queda a nombre de quien llama. Solo se puede desde `PENDING`. */
  assign: (invoiceId: number, lineId: number, body: { userId?: number }): Promise<InvoiceLine> =>
    put<InvoiceLine>(`/invoices/${invoiceId}/lines/${lineId}/assign`, body),

  approve: (id: number, body: DecisionBody): Promise<InvoiceDetail> =>
    post<InvoiceDetail>(`/invoices/${id}/approve`, body),

  rejectDiscrepancy: (id: number, body: DecisionBody): Promise<InvoiceDetail> =>
    post<InvoiceDetail>(`/invoices/${id}/reject-discrepancy`, body),

  close: (id: number): Promise<InvoiceDetail> => post<InvoiceDetail>(`/invoices/${id}/close`),
};
