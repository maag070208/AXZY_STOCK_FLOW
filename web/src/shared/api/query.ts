import type { PageQuery } from "./types";

/**
 * Convierte la consulta de la tabla en parámetros de la API.
 *
 * Los filtros llegan como los entrega el kit (escalares por columna). Un valor vacío **no se
 * manda**: `active=` sería un filtro inválido, y la API rechaza lo que no entiende en lugar de
 * ignorarlo (docs/api/convenciones.md §1.4). Lo comparten todos los módulos con paginación.
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

/** Quita los vacíos de un objeto de parámetros: un filtro sin valor no se envía. */
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
