/** Traslados y tránsito (docs/api/traslados.md). `daysInTransit` lo cuenta el servidor, no el cliente. */
export type TransferHeader = {
  id: number;
  code: string;
  fromWarehouseId: number;
  fromWarehouseCode: string;
  toWarehouseId: number;
  toWarehouseCode: string;
  status: string;
  lineCount: number;
  totalUnits: string;
  note: string | null;
  dispatchedByName: string | null;
  dispatchedAt: string | null;
  receivedByName: string | null;
  receivedAt: string | null;
};

export type InTransit = {
  id: number;
  code: string;
  fromWarehouseCode: string;
  toWarehouseCode: string;
  status: string;
  lineCount: number;
  totalUnits: string;
  dispatchedAt: string | null;
  daysInTransit: number;
  dispatchedByName: string | null;
};

/** Una línea del traslado (`GET /v1/transfers/{id}`). Las cantidades van como texto decimal. */
export type TransferLine = {
  id: number;
  productId: number;
  productCode: string;
  productName: string;
  quantity: string;
  fromLocationId: number | null;
  fromLocationDisplayName: string | null;
  note: string | null;
};

/** El detalle: la cabecera va bajo `transfer`, no al nivel de la respuesta (docs/api/traslados.md). */
export type TransferDetail = { transfer: TransferHeader; lines: TransferLine[] };
