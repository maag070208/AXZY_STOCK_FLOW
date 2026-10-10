import {
  ITDataTable,
  type Column,
  type ColumnFilters,
  type ITDataTableFetchParams,
} from "@axzydev/axzy_ui_system";
import type { Page, PageQuery } from "@shared/api/types";

export interface DataTableProps<T extends Record<string, unknown>> {
  columns: Column<T>[];
  /** Una página del servidor, con el contrato de la API. El envoltorio traduce. */
  fetchPage: (query: PageQuery) => Promise<Page<T>>;
  /** Filtros de fuera de la tabla: la búsqueda libre, el selector de bodega. */
  externalFilters?: ColumnFilters;
  /** Cambiar este valor vuelve a pedir la página actual (tras guardar, por ejemplo). */
  reloadTrigger?: number | string | boolean;
  onRowClick?: (row: T) => void;
}

/**
 * El envoltorio de `ITDataTable` (docs/arquitectura/web-react.md §3.1).
 *
 * **Traduce un contrato que no coincide.** El kit pide `{ page, limit, filters,
 * sort }` con la página **desde 1**; la API recibe `{ page, size, q, sort }` con
 * la página **desde 0** y el orden como `"campo,direccion"`. Sin esta clase, cada
 * pantalla repetiría la traducción (y con ella el error de la página ±1).
 *
 * **Quita los casts.** En CYC hay 85 `as unknown as`, 29 solo para `Column`: un
 * cast doble apaga el compilador, así que un campo renombrado en la respuesta no
 * da error en ningún sitio. Aquí las columnas se tipan con `T` y el envoltorio
 * fija los valores por defecto de §3.1, de modo que una pantalla solo pasa lo
 * que cambia.
 */
export function DataTable<T extends Record<string, unknown>>({
  columns,
  fetchPage,
  externalFilters,
  reloadTrigger,
  onRowClick,
}: DataTableProps<T>) {
  const loadPage = async (params: ITDataTableFetchParams) => {
    const { q, ...rest } = params.filters;
    const search = typeof q === "string" && q.trim() !== "" ? q.trim() : undefined;

    const page = await fetchPage({
      page: params.page - 1,
      size: params.limit,
      sort: params.sort ? `${params.sort.key},${params.sort.direction}` : undefined,
      q: search,
      filters: rest,
    });

    return { data: page.items, total: page.totalItems };
  };

  return (
    <ITDataTable<T>
      columns={columns}
      fetchData={loadPage}
      externalFilters={externalFilters}
      reloadTrigger={reloadTrigger}
      onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
      defaultItemsPerPage={25}
      itemsPerPageOptions={[25, 50, 100]}
      layout="fixed"
      density="compact"
      size="sm"
      virtualized
      virtualizedMaxHeight={400}
      rowHeight={50}
    />
  );
}
