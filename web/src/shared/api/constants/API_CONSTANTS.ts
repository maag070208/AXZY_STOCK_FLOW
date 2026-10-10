/**
 * De dónde sale la URL base de la API.
 *
 * Prioridad: 1) configuración en runtime (nginx escribe `config.js` con la URL
 * del despliegue), 2) `VITE_API_URL` en el build, 3) ruta relativa.
 *
 * **La ruta relativa es el caso normal**, y es deliberado: en desarrollo Vite
 * proxya `/v1` a la API y en producción lo hace nginx. El navegador nunca llama
 * a otro origen, así que no hay CORS ni URL que se rompa al cambiar de entorno.
 */
type RuntimeConfig = { API_URL?: string };

declare global {
  interface Window {
    __APP_CONFIG__?: RuntimeConfig;
  }
}

/**
 * **Ya incluye `/v1`.** Las rutas de los módulos van sin el prefijo:
 * `get("/products")`, nunca `get("/v1/products")` — que sería `/v1/v1/...`
 * (docs/arquitectura/web-fsd.md §2.3).
 */
const BASE_URL: string =
  window.__APP_CONFIG__?.API_URL ?? (import.meta.env.VITE_API_URL as string | undefined) ?? "/v1";

export const API_CONSTANTS = {
  BASE_URL,
  TIMEOUT: 30_000,
} as const;

/** Rutas que no pasan por la lógica de sesión: un `401` aquí es la respuesta, no una sesión vencida. */
export const AUTH_PATHS = ["/auth/login", "/auth/refresh"] as const;
