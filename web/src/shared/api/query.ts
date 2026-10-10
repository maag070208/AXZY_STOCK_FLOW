import type { PageQuery } from "./types";

/**
 * Converts the table query into API parameters.
 *
 * The filters arrive as the kit delivers them (scalars per column). An empty value **is not
 * sends**: `active=` would be an invalid filter, and the API rejects what it does not understand instead of
 * ignoring it (docs/api/convenciones.md §1.4). All modules with pagination share it.
 */
export const toQueryParams = (query: PageQuery): Record<string, string | number | boolean> => {
  const params: Record<string, string | number | boolean> = {
    page: query.page,
    size: query.size,
  };
  if (query.sort) params.sort = query.sort;
  if (query.q) params.q = query.q;
  for (const [key, value] of Object.entries(query.filters)) {
    if (value === null || value === undefined || value === "") continue;
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
      params[key] = value;
    }
  }
  return params;
};

/** Removes the empty values from a parameters object: a filter without a value is not sent. */
export const compactParams = <T extends Record<string, string | number | boolean | undefined | null>>(
  params: T,
): Record<string, string | number | boolean> => {
  const out: Record<string, string | number | boolean> = {};
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === "") continue;
    out[key] = value;
  }
  return out;
};
