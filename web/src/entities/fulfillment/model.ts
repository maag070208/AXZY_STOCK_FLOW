/** Surtido (docs/api/surtido.md). `exportStatus` cuenta el intento honesto: sin ERP sale `FAILED`. */
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

/** Una línea de la orden. Las cantidades van como texto decimal; `suppliedQuantity` ya salió del inventario. */
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

/** Resultado de un intento de exportación (`POST /v1/orders/{id}/export`). No es la orden. */
export type ExportAttempt = { attempts: number; status: "SENT" | "FAILED" | "PENDING"; lastError: string | null };

export type PackBody = { quantity: number };
export type ShortageBody = { lineId: number; quantity: number; reason: string; note?: string };
/** Sin `userId` la línea queda a nombre de quien llama; el cuerpo vacío es válido. */
export type AssignBody = { userId?: number; locationId?: number };
