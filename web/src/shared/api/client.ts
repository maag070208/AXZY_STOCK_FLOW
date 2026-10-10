import axios, { AxiosError, type AxiosRequestConfig, type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import { API_CONSTANTS, AUTH_PATHS } from "./constants/API_CONSTANTS";
import { getSessionRefreshToken, getSessionToken, handleUnauthorized, updateSessionTokens } from "./session";
import { ApiError, CLIENT_HEADERS, type ErrorBody } from "./types";
import i18n from "@shared/i18n";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE HTTP CLIENT
 *
 * Three things it does that are not obvious:
 *
 *  1. **Renews the token only once, even when ten requests fail at the same time.**
 *     Without the shared promise (`refreshPromise`), ten simultaneous `401`s
 *     trigger ten renewals; since the refresh **rotates** the token, nine
 *     fail and the session closes by itself. It is the classic failure of this pattern.
 *  2. **Does not close the session when the response explains why.** A `401` of
 *     `/auth/login` is "wrong password", not an expired session; and
 *     `ACCOUNT_DEACTIVATED` has to reach the screen so it can say so.
 *  3. **Normalizes the error to [ApiError].** The UI reads `code`; the `message`
 *     is there to show.
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

/** A single simultaneous renewal for all the requests. */
let refreshPromise: Promise<string | null> | null = null;

const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = getSessionRefreshToken();
  if (!refreshToken) return null;
  refreshPromise ??= (async () => {
    try {
      // `axios` used directly, not `instance`: the renewal must not go through the
      // interceptor that calls it, or a failed refresh would retry itself.
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
      // `ACCOUNT_DEACTIVATED` does not close the session silently: the screen has
      // that to be able to say "your account is deactivated", which is different information
      // of "the session expired".
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

/** Uploads a file (`multipart/form-data`): the browser sets the `boundary` with the `FormData`. */
export const postFile = <T>(url: string, form: FormData, config?: AxiosRequestConfig): Promise<T> =>
  request<T>({
    ...config,
    method: "POST",
    url,
    data: form,
    headers: { ...config?.headers, "Content-Type": "multipart/form-data" },
  });

export { instance as axiosInstance };
