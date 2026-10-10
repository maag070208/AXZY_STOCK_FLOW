/** Transfers and transit (docs/api/traslados.md). `daysInTransit` is counted by the server, not by the client. */
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

/** A line of the transfer (`GET /v1/transfers/{id}`). The quantities go as decimal text. */
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

/** The detail: the header is under `transfer`, not at the response level (docs/api/traslados.md). */
export type TransferDetail = { transfer: TransferHeader; lines: TransferLine[] };
