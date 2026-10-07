/**
 * Constantes del cliente HTTP.
 *
 * La URL base se resuelve en runtime, no en build: la misma imagen se despliega
 * contra desarrollo y contra producción. Si se fijara con `VITE_API_URL` habría
 * que recompilar para cambiar de backend, que es justo lo que el legacy hacía
 * con el puerto y el `http` hardcodeados.
 */
const BASE_URL =
  (typeof window !== "undefined" ? window.__APP_CONFIG__?.API_URL : undefined) ||
  import.meta.env.VITE_API_URL ||
  "/v1";

export const API_CONSTANTS = {
  BASE_URL,
  /** 30 s: el import de catálogos es la llamada más lenta del contrato. */
  TIMEOUT: 30_000,
  HEADERS: {
    "Content-Type": "application/json",
  },
  /**
   * Canal declarado en `X-Client-Channel` (CONTRATO_API §1.6). La auditoría lo
   * registra; `MOBILE_OFFLINE` dispara el camino de sincronización diferida.
   * La web siempre es `WEB`.
   */
  CHANNEL: "WEB",
} as const;
