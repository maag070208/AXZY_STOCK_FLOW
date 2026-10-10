import { get } from "@shared/api/client";
import { compactParams, toQueryParams } from "@shared/api/query";
import type { Page, PageQuery } from "@shared/api/types";
import type { KardexLine, StockLine } from "./model";

export type StockFilters = { productCode?: string; locationCode?: string; onlyWithStock?: boolean };
export type KardexFilters = { productCode?: string; moveType?: string; from?: string; to?: string };

export const inventoryApi = {
  /** Existencias por ubicación. Filtros propios del endpoint: el código de producto y la ubicación. */
  stock: (query: PageQuery, filters: StockFilters): Promise<Page<StockLine>> =>
    get<Page<StockLine>>("/inventory/stock", {
      params: { ...toQueryParams(query), ...compactParams({ ...filters }) },
    }),

  /** El kardex: el libro de movimientos. Solo lectura, nunca se edita (ADR-0015). */
  kardex: (query: PageQuery, filters: KardexFilters): Promise<Page<KardexLine>> =>
    get<Page<KardexLine>>("/inventory/kardex", {
      params: { ...toQueryParams(query), ...compactParams({ ...filters }) },
    }),
};
