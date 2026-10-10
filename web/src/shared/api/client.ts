import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import { API_CONSTANTS, AUTH_PATHS } from "./constants/API_CONSTANTS";
import { getSessionRefreshToken, getSessionToken, handleUnauthorized, updateSessionTokens } from "./session";
import { ApiError, CLIENT_HEADERS, type ErrorBody } from "./types";
import i18n from "@shared/i18n";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL CLIENTE HTTP
 *
 * Tres cosas que hace y que no son obvias:
 *
 *  1. **Renueva el token una sola vez, aunque fallen diez peticiones a la vez.**
 *     Sin la promesa compartida (`refreshPromise`), diez `401` simultáneos
 *     disparan diez renovaciones; como el refresh **rota** el token, nueve
 *     fallan y la sesión se cierra sola. Es el fallo clásico de este patrón.
 *  2. **No cierra la sesión cuando la respuesta explica por qué.** Un `401` de
 *     `/auth/login` es «contraseña incorrecta», no una sesión vencida; y
 *     `ACCOUNT_DEACTIVATED` tiene que llegar a la pantalla para poder decirlo.
 *  3. **Normaliza el error a [ApiError].** La interfaz lee `code`; el `message`
 *     es para mostrar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

const instance = axios.create({
  baseURL: API_CONSTANTS.BASE_URL,
  timeout: API_CONSTANTS.TIMEOUT,
  headers: { "Content-Type": "application/json" },
});

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getSessionToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  Object.entries(CLIENT_HEADERS).forEach(([name, value]) => {
    config.headers[name] = value;
  });
  return config;
});

type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

/** Una sola renovación simultánea para todas las peticiones. */
let refreshPromise: Promise<string | null> | null = null;

const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = getSessionRefreshToken();
  if (!refreshToken) return null;
  refreshPromise ??= (async () => {
    try {
      // `axios` a pelo, no `instance`: la renovación no debe pasar por el
      // interceptor que la llama, o un refresh fallido se reintentaría a sí mismo.
      const { data } = await axios.post(`${API_CONSTANTS.BASE_URL}/auth/refresh`, { refreshToken });
      updateSessionTokens({ accessToken: data.accessToken, refreshToken: data.refreshToken });
      return data.accessToken as string;
    } catch {
      return null;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
};

const isAuthPath = (url?: string): boolean =>
  url !== undefined && AUTH_PATHS.some((path) => url.includes(path));

instance.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error: AxiosError) => {
    const status = error.response?.status;
    const code = (error.response?.data as ErrorBody | undefined)?.code;
    const original = error.config as RetriableConfig | undefined;

    if (status === 401 && original && !original._retry && !isAuthPath(original.url)) {
      original._retry = true;
      const token = await refreshAccessToken();
      if (token) {
        original.headers.Authorization = `Bearer ${token}`;
        return instance(original);
      }
    }

    if (status === 401 && !isAuthPath(original?.url)) {
      // `ACCOUNT_DEACTIVATED` no cierra la sesión en silencio: la pantalla tiene
      // que poder decir «tu cuenta está desactivada», que es información distinta
      // de «se venció la sesión».
      if (code !== "ACCOUNT_DEACTIVATED") handleUnauthorized();
    }
    return Promise.reject(error);
  },
);

const normalize = (error: unknown): never => {
  if (error instanceof ApiError) throw error;
  const axiosError = error as AxiosError<ErrorBody>;
  const body = axiosError.response?.data;
  const status = axiosError.response?.status ?? 0;
  const message =
    body?.message ??
    (status === 0 ? i18n.t("errors.apiUnreachable") : i18n.t("errors.unexpected"));
  throw new ApiError(status, message, body?.code, body?.details);
};

const request = async <T>(config: AxiosRequestConfig): Promise<T> => {
  try {
    const response: AxiosResponse<T> = await instance.request<T>(config);
    return response.data;
  } catch (error) {
    return normalize(error);
  }
};

export const get = <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
  request<T>({ ...config, method: "GET", url });

export const post = <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
  request<T>({ ...config, method: "POST", url, data });

export const put = <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
  request<T>({ ...config, method: "PUT", url, data });

export const remove = <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
  request<T>({ ...config, method: "DELETE", url });

/** Sube un archivo (`multipart/form-data`): el navegador pone el `boundary` con el `FormData`. */
export const postFile = <T>(url: string, form: FormData, config?: AxiosRequestConfig): Promise<T> =>
  request<T>({
    ...config,
    method: "POST",
    url,
    data: form,
    headers: { ...config?.headers, "Content-Type": "multipart/form-data" },
  });

export { instance as axiosInstance };
