/**
 * Where the API base URL comes from.
 *
 * Priority: 1) runtime configuration (nginx writes `config.js` with the URL
 * of the deployment), 2) `VITE_API_URL` at build time, 3) relative path.
 *
 * **The relative path is the normal case**, and it is deliberate: in development Vite
 * proxies `/v1` to the API and in production nginx does it. The browser never calls
 * to another origin, so there is no CORS and no URL that breaks when changing environment.
 */
type RuntimeConfig = { API_URL?: string };

declare global {
  interface Window {
    __APP_CONFIG__?: RuntimeConfig;
  }
}

/**
 * **Already includes `/v1`.** The module routes go without the prefix:
 * `get("/products")`, never `get("/v1/products")` — which would be `/v1/v1/...`
 * (docs/arquitectura/web-fsd.md §2.3).
 */
const BASE_URL: string =
  window.__APP_CONFIG__?.API_URL ?? (import.meta.env.VITE_API_URL as string | undefined) ?? "/v1";

export const API_CONSTANTS = {
  BASE_URL,
  TIMEOUT: 30_000,
} as const;

/** Routes that do not go through the session logic: a `401` here is the response, not an expired session. */
export const AUTH_PATHS = ["/auth/login", "/auth/refresh"] as const;
