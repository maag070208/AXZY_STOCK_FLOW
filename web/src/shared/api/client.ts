import axios, {
  AxiosError,
  AxiosHeaders,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";
import { API_CONSTANTS } from "./constants/API_CONSTANTS";
import {
  getSessionRefreshToken,
  getSessionToken,
  handleTokensRevoked,
  handleUnauthorized,
  updateSessionTokens,
} from "./session";
import type { AuthTokensResponse, ErrorResponse } from "./types";
import { clientTimezone, newId, type RequestOptions } from "./lib/http";

/**
 * Error único del cliente. **El `code` es el contrato**: la UI decide con él
 * (§1.3) y nunca comparando `message`, que se traduce y se reescribe.
 */
export class ApiError extends Error {
  public readonly status: number;
  public readonly code: string;
  public readonly details: Record<string, unknown> | null;
  /** `X-Request-Id` de la petición fallida, para cruzarla con la auditoría. */
  public readonly requestId: string | null;

  constructor(params: {
    status: number;
    code: string;
    message: string;
    details?: Record<string, unknown> | null;
    requestId?: string | null;
  }) {
    super(params.message);
    this.name = "ApiError";
    this.status = params.status;
    this.code = params.code;
    this.details = params.details ?? null;
    this.requestId = params.requestId ?? null;
  }
}

/** Códigos que significan «vuelve a iniciar sesión». */
const SESSION_END_CODES = new Set(["TOKEN_REUSED"]);

/* --------------------------------------------------------------- instancias */

const axiosInstance = axios.create({
  baseURL: API_CONSTANTS.BASE_URL,
  timeout: API_CONSTANTS.TIMEOUT,
  headers: API_CONSTANTS.HEADERS,
});

/**
 * Instancia aparte para el refresh: si usara la de arriba, un `401` del propio
 * refresh entraría al interceptor y se reintentaría a sí mismo.
 */
const refreshInstance = axios.create({
  baseURL: API_CONSTANTS.BASE_URL,
  timeout: API_CONSTANTS.TIMEOUT,
  headers: API_CONSTANTS.HEADERS,
});

/* --------------------------------------------------------- request: cabeceras */

axiosInstance.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getSessionToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    // Correlación con la auditoría y canal declarado (§1.6). Desde la web el
    // canal siempre es WEB; `MOBILE_OFFLINE` es del cliente móvil.
    config.headers["X-Request-Id"] = newId();
    config.headers["X-Client-Channel"] = API_CONSTANTS.CHANNEL;
    config.headers["X-Client-Timezone"] = clientTimezone();
    return config;
  },
  (error: AxiosError) => Promise.reject(error)
);

/* -------------------------------------------------------- response: refresh */

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

/**
 * Una sola renovación simultánea para todas las peticiones: si diez listados
 * reciben `401` a la vez, no se disparan diez refresh (que además rotarían el
 * token diez veces y el servidor detectaría reuso).
 */
let refreshPromise: Promise<string | null> | null = null;

const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = getSessionRefreshToken();
  if (!refreshToken) return null;

  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const { data } = await refreshInstance.post<AuthTokensResponse>("/auth/refresh", {
          refreshToken,
        });
        updateSessionTokens({
          accessToken: data.accessToken,
          refreshToken: data.refreshToken,
        });
        return data.accessToken;
      } catch (error) {
        const err = error as AxiosError<ErrorResponse>;
        const code = err.response?.data?.code;
        if (code && SESSION_END_CODES.has(code)) {
          // Reuso detectado: la familia entera quedó revocada. No es un 401
          // cualquiera, la UI debe decir «tu sesión se cerró por seguridad».
          handleTokensRevoked(code);
        }
        return null;
      } finally {
        refreshPromise = null;
      }
    })();
  }
  return refreshPromise;
};

axiosInstance.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error: AxiosError) => {
    const status = error.response?.status;
    const code = (error.response?.data as ErrorResponse | undefined)?.code;
    const original = error.config as RetriableConfig | undefined;

    // Un access token vencido se renueva **una sola vez** por petición (§1.2).
    if (
      status === 401 &&
      original &&
      !original._retry &&
      !original.url?.includes("/auth/refresh") &&
      !original.url?.includes("/auth/login")
    ) {
      original._retry = true;
      const token = await refreshAccessToken();
      if (token) {
        original.headers.Authorization = `Bearer ${token}`;
        // El reintento también necesita correlación propia: es otra petición.
        original.headers["X-Request-Id"] = newId();
        return axiosInstance(original);
      }
      // Sin refresh posible, la sesión terminó. Aun así se propaga el error
      // original para que la UI muestre el `code` real.
      if (!code || !SESSION_END_CODES.has(code)) {
        handleUnauthorized();
      }
    } else if (status === 401 && code && SESSION_END_CODES.has(code)) {
      handleTokensRevoked(code);
    }

    return Promise.reject(error);
  }
);

/* ------------------------------------------------------------ capa de error */

/** Normaliza cualquier fallo a `ApiError` conservando `code` y `details`. */
const toApiError = (error: unknown): ApiError => {
  if (error instanceof ApiError) return error;

  if (axios.isAxiosError(error)) {
    const response = error.response;
    const body = response?.data as Partial<ErrorResponse> | undefined;

    // El contrato de error es `{ code, message, details }` en toda la API. Si
    // el backend no responde (red caída, 502 de nginx), no hay `code`: se usa
    // uno sintético para que la UI no tenga que distinguir "sin code" de "con
    // code" en cada `catch`.
    const code =
      body?.code ??
      (error.code === "ECONNABORTED"
        ? "TIMEOUT"
        : response
          ? `HTTP_${response.status}`
          : "NETWORK_ERROR");

    return new ApiError({
      status: response?.status ?? 0,
      code,
      message: body?.message ?? error.message ?? "No se pudo completar la petición",
      details: body?.details ?? null,
      requestId: (response?.headers?.["x-request-id"] as string | undefined) ?? null,
    });
  }

  return new ApiError({
    status: 0,
    code: "UNKNOWN_ERROR",
    message: error instanceof Error ? error.message : "Error desconocido",
  });
};

/* ------------------------------------------------------------- peticiones */

interface HttpConfig extends AxiosRequestConfig {
  /** Cuerpo realmente enviado: `delete` de axios usa `data` en la config. */
  data?: unknown;
}

const request = async <T>(
  method: "get" | "post" | "put" | "patch" | "delete",
  url: string,
  body?: unknown,
  options?: RequestOptions
): Promise<T> => {
  // Se copian sólo las cabeceras con valor: una cabecera en `undefined` sirve
  // para **borrar** la que puso `API_CONSTANTS` (el `Content-Type` del
  // multipart), no para enviarla.
  const headers = new AxiosHeaders();
  for (const [name, value] of Object.entries(options?.headers ?? {})) {
    if (value !== undefined) headers.set(name, value);
  }

  // La clave de idempotencia se exige en las operaciones que mueven inventario
  // o crean documentos (§1.5). Se acepta en opciones o en el cuerpo, porque el
  // contrato admite ambas formas.
  const bodyKey =
    body && typeof body === "object" && "idempotencyKey" in body
      ? String((body as { idempotencyKey?: unknown }).idempotencyKey ?? "")
      : "";
  const idempotencyKey = options?.idempotencyKey ?? (bodyKey || undefined);
  if (idempotencyKey) {
    headers.set("Idempotency-Key", idempotencyKey);
  }

  const config: HttpConfig = {
    params: options?.params,
    signal: options?.signal,
    headers,
    ...(method === "delete" && body !== undefined ? { data: body } : {}),
  };

  try {
    const response = await axiosInstance.request<T>({
      method,
      url,
      ...(method === "get" || method === "delete" ? {} : { data: body }),
      ...config,
    });
    return response.data;
  } catch (error) {
    throw toApiError(error);
  }
};

/**
 * Respuestas sin cuerpo (`204`): logout, cierre de sesión, cambio de contraseña.
 * axios devuelve `""` como data y ese valor no se debe tipar como el DTO.
 */
const requestVoid = async (
  method: "post" | "put" | "patch" | "delete",
  url: string,
  body?: unknown,
  options?: RequestOptions
): Promise<void> => {
  await request<unknown>(method, url, body, options);
};

/* ------------------------------------------------------- descarga de archivos */

/**
 * Descarga binaria (exportaciones XLSX/CSV del §9).
 *
 * Se separa del resto porque `responseType: "blob"` cambia el manejo del error:
 * cuando el backend falla, el cuerpo del error llega **dentro del blob** y hay
 * que leerlo para recuperar el `code`.
 */
export const downloadFile = async (
  url: string,
  options?: RequestOptions & { fallbackFilename?: string }
): Promise<{ blob: Blob; filename: string }> => {
  try {
    const response = await axiosInstance.request<Blob>({
      method: "get",
      url,
      params: options?.params,
      signal: options?.signal,
      responseType: "blob",
    });

    const disposition = (response.headers["content-disposition"] as string | undefined) ?? "";
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
    const filename = match?.[1] ? decodeURIComponent(match[1]) : (options?.fallbackFilename ?? "export");

    return { blob: response.data, filename };
  } catch (error) {
    if (axios.isAxiosError(error) && error.response?.data instanceof Blob) {
      // El error viene envuelto en el blob: se lee como texto para rescatar el
      // contrato `{ code, message, details }` en lugar de reportar "sin code".
      const raw = await error.response.data.text();
      try {
        const parsed = JSON.parse(raw) as ErrorResponse;
        throw new ApiError({
          status: error.response.status,
          code: parsed.code ?? `HTTP_${error.response.status}`,
          message: parsed.message ?? "No se pudo descargar el archivo",
          details: parsed.details ?? null,
        });
      } catch (parseError) {
        if (parseError instanceof ApiError) throw parseError;
      }
    }
    throw toApiError(error);
  }
};

export const get = <T>(url: string, options?: RequestOptions): Promise<T> =>
  request<T>("get", url, undefined, options);

export const post = <T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> =>
  request<T>("post", url, body, options);

export const put = <T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> =>
  request<T>("put", url, body, options);

export const patch = <T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> =>
  request<T>("patch", url, body, options);

export const remove = <T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> =>
  request<T>("delete", url, body, options);

/** Envoltura con nombre corto, igual que en el repo de referencia. */
export const api = {
  get,
  post,
  put,
  patch,
  delete: remove,
  requestVoid,
  downloadFile,
};

export { axiosInstance };
