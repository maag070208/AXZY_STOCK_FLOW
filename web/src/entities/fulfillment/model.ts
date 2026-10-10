/** Fulfillment (docs/api/surtido.md). `exportStatus` records the attempt honestly: without an ERP it comes out `FAILED`. */
export type OrderHeader = {
  id: number;
  orderNumber: string;
  packagingDate: string | null;
  status: string;
  customerCode: string | null;
  concept: string | null;
  converted: boolean;
  convertedAt: string | null;
  exportStatus: string | null;
  exportAttempts: number;
  exportLastError: string | null;
  totalLines: number;
  packagedLines: number;
};

/** A line of the order. The quantities go as decimal text; `suppliedQuantity` has already left the inventory. */
export type OrderLine = {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  unitMeasure: string;
  quantity: string;
  suppliedQuantity: string;
  supplied: boolean;
  status: string;
  assignedUserId: number | null;
  assignedUserName: string | null;
  locationId: number | null;
  locationDisplayName: string | null;
};

export type OrderDetail = OrderHeader & { lines: OrderLine[] };

/** Result of an export attempt (`POST /v1/orders/{id}/export`). It is not the order. */
export type ExportAttempt = { attempts: number; status: "SENT" | "FAILED" | "PENDING"; lastError: string | null };

export type PackBody = { quantity: number };
export type ShortageBody = { lineId: number; quantity: number; reason: string; note?: string };
/** Without `userId` the line is left under the caller's name; the empty body is valid. */
export type AssignBody = { userId?: number; locationId?: number };
