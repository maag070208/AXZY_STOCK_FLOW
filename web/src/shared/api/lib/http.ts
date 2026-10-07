/**
 * Utilidades internas del cliente HTTP que no dependen del contrato.
 */

/** Sobre de paginación de toda lista (CONTRATO_API §1.4). */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/**
 * Identificador único para `X-Request-Id` e `Idempotency-Key`.
 *
 * `crypto.randomUUID` sólo existe en contexto seguro (https o localhost); la
 * web interna entra por `http://IP`, así que se cae a `getRandomValues`, que sí
 * está disponible fuera de contexto seguro. Es el mismo defecto que tumbaba
 * pantallas del legacy.
 */
export const newId = (): string => {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
};

/**
 * Zona horaria del navegador en formato IANA (`America/Mexico_City`) para
 * `X-Client-Timezone`. El turno de noche necesita la fecha de negocio, no la del
 * reloj (§1.7), y el servidor no puede adivinarla.
 */
export const clientTimezone = (): string => {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
};

/** Parámetros de consulta admitidos por los listados del contrato. */
export interface QueryParams {
  page?: number;
  size?: number;
  sort?: string;
  q?: string;
  [key: string]: string | number | boolean | undefined | null;
}

/**
 * Opciones propias de una petición.
 *
 * `idempotencyKey` es obligatorio en las operaciones que mueven inventario o
 * crean documentos (§1.5): si se omite, el cliente genera una clave nueva por
 * intento, que es lo correcto para una acción deliberada del usuario pero **no**
 * para un reintento automático. Por eso las llamadas de reintento deben pasar su
 * propia clave estable.
 */
export interface RequestOptions {
  idempotencyKey?: string;
  params?: QueryParams;
  signal?: AbortSignal;
  /**
   * Cabeceras extra. El valor `undefined` **borra** la cabecera por defecto; es
   * lo que necesita `multipart/form-data`, al que no se le puede fijar el
   * `Content-Type` a mano porque el navegador añade el `boundary`.
   */
  headers?: Record<string, string | undefined>;
}
