import i18n from "@shared/i18n";
import { ApiError } from "./types";

/**
 * The text the interface shows for a failure.
 *
 * It is translated by **code**, never by the message that comes from the API: that text is for people and may change
 * because the operator reads it (ADR-0004), and the web does not show it. A code without a translation falls back to
 * generic text of `fallbackKey`; a network failure (`status` 0) says that there is no connection to the API.
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
