import type {
  Location,
  LocationCreateRequest,
  LocationKind,
  PageParams,
  Product,
  ProductCreateRequest,
  ProductImportReport,
  ProductSort,
  Warehouse,
} from "@shared/api";

/** Alias de los DTOs del contrato que esta entidad expone hacia arriba. */
export type ProductDto = Product;
export type LocationDto = Location;
export type WarehouseDto = Warehouse;
export type ProductImportReportDto = ProductImportReport;

export type { LocationCreateRequest, ProductCreateRequest, LocationKind, ProductSort };

/** Respuesta de `GET /v1/warehouses`: listado sin sobre de paginación. */
export interface WarehouseList {
  items: Warehouse[];
}

/** Filtros del listado de productos. `q` es la búsqueda libre del contrato. */
export interface CatalogListParams extends PageParams {
  active?: boolean;
}

/** Cuerpo del `DELETE` lógico de producto: el motivo es obligatorio. */
export interface DeactivateProductRequest {
  reason: string;
  /** Desactivar un producto **con stock** exige forzarlo explícitamente. */
  force?: boolean;
}
