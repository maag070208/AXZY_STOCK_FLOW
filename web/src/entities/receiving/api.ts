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

  /** The inbox does not use the pagination envelope: it is `{ items: [...] }` (docs/api/recepcion.md). */
  pendingApproval: (): Promise<{ items: PendingApproval[] }> =>
    get<{ items: PendingApproval[] }>("/invoices/pending-approval"),

  /** Creation. The API responds with the reference (201), not the invoice: the detail is requested separately. */
  create: (body: NewInvoice): Promise<{ id: number; invoiceNumber: string }> =>
    post<{ id: number; invoiceNumber: string }>("/invoices", body),

  detail: (id: number): Promise<InvoiceDetail> => get<InvoiceDetail>(`/invoices/${id}`),

  /** Responds with the line, not the invoice. */
  capture: (invoiceId: number, lineId: number, body: CaptureBody): Promise<InvoiceLine> =>
    post<InvoiceLine>(`/invoices/${invoiceId}/lines/${lineId}/capture`, body),

  /** Without `userId` the line is left under the caller's name. It can only be done from `PENDING`. */
  assign: (invoiceId: number, lineId: number, body: { userId?: number }): Promise<InvoiceLine> =>
    put<InvoiceLine>(`/invoices/${invoiceId}/lines/${lineId}/assign`, body),

  approve: (id: number, body: DecisionBody): Promise<InvoiceDetail> =>
    post<InvoiceDetail>(`/invoices/${id}/approve`, body),

  rejectDiscrepancy: (id: number, body: DecisionBody): Promise<InvoiceDetail> =>
    post<InvoiceDetail>(`/invoices/${id}/reject-discrepancy`, body),

  close: (id: number): Promise<InvoiceDetail> => post<InvoiceDetail>(`/invoices/${id}/close`),
};
