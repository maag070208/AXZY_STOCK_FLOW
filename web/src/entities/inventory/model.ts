/**
 * Existencias y kardex (docs/api/inventario.md). Las cantidades son **cadenas** con tres decimales:
 * JSON no tiene decimales de precisión arbitraria, y un `number` de JavaScript suma con errores.
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
