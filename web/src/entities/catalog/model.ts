/**
 * El catálogo: productos, ubicaciones y bodegas (docs/api/catalogo.md).
 *
 * Los tipos son **alias y no interfaces** a propósito: `ITDataTable<T>` exige
 * `T extends Record<string, unknown>`, y una `interface` no encaja ahí sin un
 * cast. Los alias evitan el `as unknown as` que apaga el compilador.
 */

export type Product = {
  id: number;
  productCode: string;
  name: string;
  description: string | null;
  unitMeasure: string;
  externalId: number | null;
  active: boolean;
  /** Total derivado. Viaja como **número JSON con 3 decimales**, excepción del catálogo. */
  stock: number;
};

export type Location = {
  id: number;
  warehouseId: number;
  warehouseCode: string;
  hall: string;
  code: string;
  position: string;
  levels: number;
  kind: "SHELF" | "RECEIVING" | "UNASSIGNED";
  active: boolean;
  /** **Derivado**: lo compone el servidor. La web nunca lo arma. */
  displayName: string;
};

export type Warehouse = {
  id: number;
  code: string;
  name: string;
  address: string | null;
  isDefault: boolean;
  active: boolean;
};

export type ProductInput = {
  productCode: string;
  name: string;
  description: string | null;
  unitMeasure: string;
};

export type LocationInput = {
  warehouseId: number;
  hall: string;
  code: string;
  position: string;
  levels: number;
  kind: "SHELF" | "RECEIVING";
  active?: boolean;
};

export type WarehouseInput = {
  code: string;
  name: string;
  address: string | null;
  isDefault: boolean;
};

export type Rejection = {
  rowNumber: number;
  reason: string;
};

/** El informe de la importación. `stockPersisted` es `false` hasta la fase 5. */
export type ImportReport = {
  dryRun: boolean;
  totalRows: number;
  accepted: number;
  rejected: number;
  totalStock: number;
  zeroStockCount: number;
  unitMeasures: string[];
  rejections: Rejection[];
  created: number;
  updated: number;
  skipped: number;
  persisted: number;
  stockPersisted: boolean;
};

/** El nombre visible de una ubicación, para una lista desplegable. */
export const locationLabel = (location: Location): string =>
  `${location.displayName} · ${location.warehouseCode}`;
