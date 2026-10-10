/** Recepción (docs/api/recepcion.md). */
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

/** La bandeja de aprobación: `totalDifference` es la suma de diferencias **absolutas**. */
export type PendingApproval = {
  invoiceId: number;
  invoiceNumber: string;
  receiptDate: string;
  status: string;
  pendingLines: number;
  totalDifference: string;
};

/** El detalle de una factura con sus líneas (`GET /v1/invoices/{id}`). */
export type InvoiceLine = {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  container: string | null;
  /** Cantidades como texto decimal: la API no pierde precisión. */
  quantity: string;
  boxQuantity: string;
  unitsPerBox: string;
  physicalQuantity: string | null;
  physicalBoxQuantity: string | null;
  locatedQuantity: string;
  /** `physicalQuantity - quantity`, con signo. La calcula el servidor. */
  difference: string | null;
  status: string;
  capturedByName: string | null;
  capturedAt: string | null;
  approvedByName: string | null;
  adjustmentReason: string | null;
  locationDisplayName: string | null;
};

export type InvoiceDetail = InvoiceHeader & { lines: InvoiceLine[] };

/** Alta de factura (`POST /v1/invoices`). Las cantidades van como número JSON. */
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

/** Conteo de una línea (`POST .../capture`). `physicalBoxQuantity` es una cuenta entera de cajas. */
export type CaptureBody = { physicalQuantity: number; physicalBoxQuantity?: number };

/** Motivo de una decisión de aprobar o rechazar (`POST .../approve`, `.../reject-discrepancy`). */
export type DecisionBody = { adjustmentReason: string; note?: string };

/** Las líneas que aún admiten un conteo: una aprobada o ubicada ya no se cuenta de nuevo. */
export const isCountable = (status: string): boolean => !["APPROVED", "LOCATED", "CANCELLED"].includes(status);
