/**
 * Stock and stock ledger (docs/api/inventario.md). The quantities are **strings** with three decimals:
 * JSON has no arbitrary-precision decimals, and a JavaScript `number` adds up with errors.
 */

export type StockLine = {
  productId: number;
  productCode: string;
  productName: string;
  locationId: number;
  locationDisplayName: string;
  warehouseId: number;
  warehouseCode: string;
  quantity: string;
};

export type KardexLine = {
  id: number;
  movedAt: string;
  productCode: string;
  productName: string;
  moveType: string;
  units: string;
  quantityAfter: string;
  fromLocationDisplayName: string | null;
  toLocationDisplayName: string | null;
  container: string | null;
  orderNumber: string | null;
  adjustmentReason: string | null;
  adjustmentOrigin: string | null;
  userId: number | null;
  userName: string | null;
  note: string | null;
};
