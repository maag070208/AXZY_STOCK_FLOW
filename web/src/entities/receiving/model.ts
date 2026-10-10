/** Receiving (docs/api/recepcion.md). */
export type InvoiceHeader = {
  id: number;
  invoiceNumber: string;
  receiptDate: string;
  invoiceType: string;
  status: string;
  customerCode: string | null;
  concept: string | null;
  totalLines: number;
  pendingApprovalLines: number;
  createdAt: string;
};

/** The approval inbox: `totalDifference` is the sum of **absolute** differences. */
export type PendingApproval = {
  invoiceId: number;
  invoiceNumber: string;
  receiptDate: string;
  status: string;
  pendingLines: number;
  totalDifference: string;
};

/** The detail of an invoice with its lines (`GET /v1/invoices/{id}`). */
export type InvoiceLine = {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  container: string | null;
  /** Quantities as decimal text: the API does not lose precision. */
  quantity: string;
  boxQuantity: string;
  unitsPerBox: string;
  physicalQuantity: string | null;
  physicalBoxQuantity: string | null;
  locatedQuantity: string;
  /** `physicalQuantity - quantity`, with sign. The server computes it. */
  difference: string | null;
  status: string;
  capturedByName: string | null;
  capturedAt: string | null;
  approvedByName: string | null;
  adjustmentReason: string | null;
  locationDisplayName: string | null;
};

export type InvoiceDetail = InvoiceHeader & { lines: InvoiceLine[] };

/** Invoice creation (`POST /v1/invoices`). The quantities go as JSON numbers. */
export type NewInvoiceLine = {
  productCode: string;
  container?: string | null;
  quantity: number;
  boxQuantity: number;
  unitsPerBox: number;
};

export type NewInvoice = {
  invoiceNumber: string;
  receiptDate: string;
  invoiceType: "NATIONAL" | "INTERNATIONAL";
  lines: NewInvoiceLine[];
};

/** Count of a line (`POST .../capture`). `physicalBoxQuantity` is a whole count of boxes. */
export type CaptureBody = { physicalQuantity: number; physicalBoxQuantity?: number };

/** Reason for an approve or reject decision (`POST .../approve`, `.../reject-discrepancy`). */
export type DecisionBody = { adjustmentReason: string; note?: string };

/** The lines that still accept a count: one already approved or located is not counted again. */
export const isCountable = (status: string): boolean => !["APPROVED", "LOCATED", "CANCELLED"].includes(status);
