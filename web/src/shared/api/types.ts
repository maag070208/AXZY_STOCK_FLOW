/**
 * The types that the HTTP contract shares with all the modules.
 *
 * The error envelope and the pagination envelope are defined **once** here; each module
 * adds its entity, not its wrapper (docs/api/convenciones.md §1.3 and §1.4).
 */

/** The error body, identical across all endpoints. */
export interface ErrorBody {
  code: string;
  message: string;
  details?: Record<string, string>;
}

/** An API failure, already normalized. **The UI decides with `code`, never with `message`**. */
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

/** The envelope of a paginated list. `totalItems` comes from a `count(*)` with the same filters. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Filters and sort already translated to the API contract. */
export interface PageQuery {
  /** **0-indexed**, as the API does (the kit's `ITDataTable` uses 1). */
  page: number;
  size: number;
  /** `field,direction`; absent if the user did not sort. */
  sort?: string;
  /** Free-text search (`q`). */
  q?: string;
  /** The rest of the filters, as the table delivers them. Each module knows what to do with them. */
  filters: Record<string, unknown>;
}

/** Headers the web always sends, and that the audit log uses to know where the request came from. */
export const CLIENT_HEADERS = {
  "X-Client-Channel": "WEB",
} as const;
