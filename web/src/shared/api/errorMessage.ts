import i18n from "@shared/i18n";
import { ApiError } from "./types";

/**
 * El texto que la interfaz muestra de un fallo.
 *
 * Se traduce por **código**, nunca por el mensaje que llega de la API: ese mensaje está en español
 * porque lo lee el operador (ADR-0004), y la web no lo muestra. Un código sin traducción cae al
 * texto genérico de `fallbackKey`; un fallo de red (`status` 0) dice que no hay conexión con la API.
 */
export const describeError = (error: unknown, fallbackKey: string): string => {
  if (error instanceof ApiError) {
    if (error.status === 0) return i18n.t("errors.apiUnreachable");
    if (error.code) {
      const key = `errors.api.${error.code}`;
      if (i18n.exists(key)) return i18n.t(key);
    }
  }
  return i18n.t(fallbackKey);
};
