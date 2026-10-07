/** Entidad `catalog`: productos, ubicaciones y bodegas (§3 del contrato). */
export {
  createLocation,
  createProduct,
  deactivateProduct,
  deleteLocation,
  fetchLocation,
  fetchLocations,
  fetchProduct,
  fetchProducts,
  fetchWarehouses,
  importProducts,
  updateLocation,
  updateProduct,
} from "./api/catalogApi";
export type {
  CatalogListParams,
  DeactivateProductRequest,
  LocationCreateRequest,
  LocationDto,
  LocationKind,
  ProductCreateRequest,
  ProductDto,
  ProductImportReportDto,
  ProductSort,
  WarehouseDto,
  WarehouseList,
} from "./model/types";
