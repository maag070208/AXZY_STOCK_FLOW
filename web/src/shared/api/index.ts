/**
 * Superficie pública de `shared/api`. Las entidades importan desde aquí
 * (`@shared/api`) y no de archivos internos, para que el cliente se pueda
 * cambiar sin tocar a sus consumidores.
 */
export { api, ApiError, axiosInstance, downloadFile, get, patch, post, put, remove } from "./client";
export {
  getSessionRefreshToken,
  getSessionToken,
  handleTokensRevoked,
  handleUnauthorized,
  setSessionHooks,
  updateSessionTokens,
} from "./session";
export { API_CONSTANTS } from "./constants/API_CONSTANTS";
export * from "./types";
