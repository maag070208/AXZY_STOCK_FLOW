/**
 * Los tipos que el contrato HTTP comparte con todos los módulos.
 *
 * El sobre de error y el de paginación se definen **una vez** aquí; cada módulo
 * añade su entidad, no su envoltorio (docs/api/convenciones.md §1.3 y §1.4).
 */

/** El cuerpo de error, idéntico en todos los endpoints. */
export interface ErrorBody {
  code: string;
  message: string;
  details?: Record<string, string>;
}

/** Un fallo de la API, ya normalizado. **La UI decide con `code`, nunca con `message`**. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string,
    public readonly details?: Record<string, string>,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** El sobre de un listado paginado. `totalItems` viene de un `count(*)` con los mismos filtros. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Filtros y orden ya traducidos al contrato de la API. */
export interface PageQuery {
  /** **0-indexado**, como la API (el `ITDataTable` del kit usa 1). */
  page: number;
  size: number;
  /** `campo,direccion`; ausente si el usuario no ordenó. */
  sort?: string;
  /** Búsqueda libre (`q`). */
  q?: string;
  /** El resto de filtros, tal como los entrega la tabla. Cada módulo sabe qué hacer con ellos. */
  filters: Record<string, unknown>;
}

/** Cabeceras que la web manda siempre, y que la bitácora usa para saber de dónde vino la petición. */
export const CLIENT_HEADERS = {
  "X-Client-Channel": "WEB",
} as const;
