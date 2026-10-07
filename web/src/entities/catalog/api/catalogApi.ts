import { api, get } from "@shared/api";
import type { Page, Product, ProductCreateRequest, ProductImportReport } from "@shared/api";
import type {
  CatalogListParams,
  DeactivateProductRequest,
  LocationCreateRequest,
  LocationDto,
  WarehouseList,
} from "../model/types";

/**
 * API del catálogo: productos, ubicaciones y bodegas (§3 del contrato).
 *
 * Todas las claves de ruta van **relativas** a `API_CONSTANTS.BASE_URL`, que ya
 * incluye el `/v1` obligatorio: escribirlo aquí otra vez daría `/v1/v1/...`.
 */

/* --------------------------------------------------------------- productos */

/** `sort` admitido: `productCode`, `name`, `createdAt`. */
export const fetchProducts = (params: CatalogListParams = {}): Promise<Page<Product>> =>
  get<Page<Product>>("/products", { params: { size: 50, ...params } });

export const fetchProduct = (id: number): Promise<Product> => get<Product>(`/products/${id}`);

/** `409 DUPLICATE_PRODUCT_CODE` si la clave ya existe. */
export const createProduct = (body: ProductCreateRequest): Promise<Product> =>
  api.post<Product>("/products", body);

export const updateProduct = (
  id: number,
  body: Partial<ProductCreateRequest>
): Promise<Product> => api.put<Product>(`/products/${id}`, body);

/**
 * Baja **lógica** (`active = false`). Exige `reason`. Un producto con stock
 * responde `409 PRODUCT_HAS_STOCK` con `details.stock` salvo que se fuerce.
 */
export const deactivateProduct = (
  id: number,
  body: DeactivateProductRequest
): Promise<void> => api.requestVoid("delete", `/products/${id}`, body);

/**
 * Importa CSV o XLSX. Con `dryRun=true` devuelve el informe **sin escribir
 * nada**: es la forma de que el usuario vea las filas rechazadas con su número
 * de fila antes de comprometer el catálogo.
 */
export const importProducts = (
  file: File,
  dryRun: boolean
): Promise<ProductImportReport> => {
  const form = new FormData();
  form.append("file", file);
  form.append("dryRun", String(dryRun));
  // `Content-Type` en `undefined` borra el `application/json` por defecto: sin
  // eso el navegador no añade el `boundary` del multipart y el backend rechaza
  // el archivo.
  return api.post<ProductImportReport>("/products/import", form, {
    headers: { "Content-Type": undefined },
  });
};

/* -------------------------------------------------------------- ubicaciones */

export const fetchLocations = (
  params: { warehouseId?: number; kind?: string } & Record<string, unknown> = {}
): Promise<Page<LocationDto>> =>
  get<Page<LocationDto>>("/locations", { params: { size: 200, ...params } });

export const fetchLocation = (id: number): Promise<LocationDto> =>
  get<LocationDto>(`/locations/${id}`);

export const createLocation = (body: Record<string, unknown>): Promise<LocationDto> =>
  api.post<LocationDto>("/locations", body);

export const updateLocation = (
  id: number,
  body: Partial<LocationCreateRequest>
): Promise<LocationDto> => api.put<LocationDto>(`/locations/${id}`, body);

export const deleteLocation = (id: number, reason: string): Promise<void> =>
  api.requestVoid("delete", `/locations/${id}`, { reason });

/* ----------------------------------------------------------------- bodegas */

export const fetchWarehouses = (): Promise<WarehouseList> => get<WarehouseList>("/warehouses");
