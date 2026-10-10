import {
  ITDataTable,
  type Column,
  type ColumnFilters,
  type ITDataTableFetchParams,
} from "@axzydev/axzy_ui_system";
import type { Page, PageQuery } from "@shared/api/types";

export interface DataTableProps<T extends Record<string, unknown>> {
  columns: Column<T>[];
  /** A server page, with the API's contract. The wrapper translates it. */
  fetchPage: (query: PageQuery) => Promise<Page<T>>;
  /** Filters outside the table: the free-text search, the warehouse selector. */
  externalFilters?: ColumnFilters;
  /** Changing this value requests the current page again (after saving, for example). */
  reloadTrigger?: number | string | boolean;
  onRowClick?: (row: T) => void;
}

/**
 * The wrapper of `ITDataTable` (docs/arquitectura/web-react.md §3.1).
 *
 * **Translates a contract that does not match.** The kit asks for `{ page, limit, filters,
 * sort }` with the page **from 1**; the API receives `{ page, size, q, sort }` with
 * the page **from 0** and the order as `"field,direction"`. Without this class, each
 * screen would repeat the translation (and with it the ±1 page error).
 *
 * **Removes the casts.** CYC has 85 `as unknown as`, 29 only for `Column`: a
 * double cast turns off the compiler, so a field renamed in the response does not
 * does not fail anywhere. Here the columns are typed with `T` and the wrapper
 * fixes the default values of §3.1, so that a screen only passes what
 * that changes.
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

  // The layout is `auto`: with `fixed`, the columns without width were shared equally and the headers overlapped.
  return (
      <ITDataTable<T>
        columns={columns}
        fetchData={loadPage}
        externalFilters={externalFilters}
        reloadTrigger={reloadTrigger}
        onRowClick={onRowClick ? (row) => onRowClick(row) : undefined}
        defaultItemsPerPage={25}
        itemsPerPageOptions={[25, 50, 100]}
        layout="auto"
        density="compact"
        size="sm"
        virtualized
        virtualizedMaxHeight={400}
        rowHeight={50}
      />
  );
}
