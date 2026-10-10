import { get, post, postFile, put, remove } from "@shared/api/client";
import { toQueryParams as toParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { ImportReport, Location, LocationInput, Product, ProductInput, Warehouse, WarehouseInput } from "./model";

export const catalogApi = {
  // ── Productos ───────────────────────────────────────────────────────────
  products: (query: PageQuery): Promise<Page<Product>> => get<Page<Product>>("/products", { params: toParams(query) }),
  product: (id: number): Promise<Product> => get<Product>(`/products/${id}`),
  createProduct: (body: ProductInput): Promise<Product> => post<Product>("/products", body),
  updateProduct: (id: number, body: ProductInput): Promise<Product> => put<Product>(`/products/${id}`, body),
  /** La baja es lógica y **exige motivo**: va en la query para no depender de un cuerpo en un DELETE. */
  deactivateProduct: (id: number, reason: string): Promise<Product> =>
    remove<Product>(`/products/${id}?reason=${encodeURIComponent(reason)}`),

  importProducts: (file: File, dryRun: boolean): Promise<ImportReport> => {
    const form = new FormData();
    form.append("file", file);
    form.append("dryRun", String(dryRun));
    return postFile<ImportReport>("/products/import", form);
  },

  // ── Ubicaciones ─────────────────────────────────────────────────────────
  locations: (query: PageQuery): Promise<Page<Location>> =>
    get<Page<Location>>("/locations", { params: toParams(query) }),
  createLocation: (body: LocationInput): Promise<Location> => post<Location>("/locations", body),
  updateLocation: (id: number, body: LocationInput): Promise<Location> => put<Location>(`/locations/${id}`, body),
  deactivateLocation: (id: number, reason: string): Promise<Location> =>
    remove<Location>(`/locations/${id}?reason=${encodeURIComponent(reason)}`),

  // ── Bodegas ─────────────────────────────────────────────────────────────
  /**
   * Sin paginación: es un catálogo cerrado y diminuto que el desplegable necesita
   * completo (docs/api/convenciones.md §1.4). Admite `q` y `active`.
   */
  warehouses: (filters?: { q?: string; active?: boolean }): Promise<{ items: Warehouse[] }> =>
    get<{ items: Warehouse[] }>("/warehouses", { params: filters }),
  createWarehouse: (body: WarehouseInput): Promise<Warehouse> => post<Warehouse>("/warehouses", body),
  updateWarehouse: (id: number, body: WarehouseInput): Promise<Warehouse> => put<Warehouse>(`/warehouses/${id}`, body),
};

export type { PageQuery };
