/**
 * The catalog: products, locations and warehouses (docs/api/catalogo.md).
 *
 * The types are **aliases and not interfaces** on purpose: `ITDataTable<T>` requires
 * `T extends Record<string, unknown>`, and an `interface` does not fit there without a
 * cast. The aliases avoid the `as unknown as` that turns off the compiler.
 */

export type Product = {
  id: number;
  productCode: string;
  name: string;
  description: string | null;
  unitMeasure: string;
  externalId: number | null;
  active: boolean;
  /** Derived total. It travels as a **JSON number with 3 decimals**, the exception in the catalog. */
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
  /** **Derived**: the server composes it. The web never builds it. */
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

/** The import report. `stockPersisted` is `false` until phase 5. */
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

/** The visible name of a location, for a dropdown. */
export const locationLabel = (location: Location): string =>
  `${location.displayName} · ${location.warehouseCode}`;
