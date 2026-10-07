import type { AppLanguage } from "@shared/i18n/config";

/**
 * Fechas y números del contrato (§1.7).
 *
 * Dos formatos que **no se pueden confundir**:
 * - **Instantes**: ISO 8601 en UTC con `Z` (`2026-10-07T21:24:37.688Z`); se
 *   pintan en la zona del navegador.
 * - **Fechas operativas**: `YYYY-MM-DD` sin hora. Para el turno de noche son la
 *   fecha de negocio, no la del reloj, así que **no se convierten de zona**:
 *   pasar `2026-10-07` a UTC puede moverlo al día 6.
 */

const localeOf = (language: AppLanguage): string => (language === "en" ? "en-US" : "es-MX");

const dateFormatter = (language: AppLanguage): Intl.DateTimeFormat =>
  new Intl.DateTimeFormat(localeOf(language), {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  });

const dateTimeFormatter = (language: AppLanguage): Intl.DateTimeFormat =>
  new Intl.DateTimeFormat(localeOf(language), {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });

/** Instante ISO → fecha y hora locales. Devuelve `—` si no hay valor. */
export const formatInstant = (
  instant: string | null | undefined,
  language: AppLanguage = "es"
): string => {
  if (!instant) return "—";
  const parsed = new Date(instant);
  return Number.isNaN(parsed.getTime()) ? "—" : dateTimeFormatter(language).format(parsed);
};

/**
 * Fecha operativa (`2026-10-07`) → texto legible **sin** pasar por UTC: se
 * construye a mediodía local para que el desfase de zona no mueva el día.
 */
export const formatOperationalDate = (
  date: string | null | undefined,
  language: AppLanguage = "es"
): string => {
  if (!date) return "—";
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return date;
  const [, year, month, day] = match;
  return dateFormatter(language).format(
    new Date(Number(year), Number(month) - 1, Number(day), 12)
  );
};

/** Fecha operativa de hoy, en la zona del navegador, como `YYYY-MM-DD`. */
export const todayOperational = (): string => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
};

/**
 * Cantidades: **3 decimales** según el contrato (`100.000`, `0.500`). En inputs
 * se muestra el número sin formato, para no romper la edición.
 */
export const formatQuantity = (
  value: number | null | undefined,
  language: AppLanguage = "es"
): string =>
  value === null || value === undefined
    ? "—"
    : new Intl.NumberFormat(localeOf(language), {
        minimumFractionDigits: 3,
        maximumFractionDigits: 3,
      }).format(value);
