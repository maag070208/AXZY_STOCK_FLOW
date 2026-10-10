/**
 * Display formats. In Mexican Spanish, which is the language of the
 * interface and of the API messages.
 */

const quantityFormat = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 0,
  // The API sends the quantity with 3 decimals (docs/api/convenciones.md §1.7).
  // They are shown only if they exist: `5193`, not `5193.000`.
  maximumFractionDigits: 3,
});

/** A quantity of inventory. It accepts a number or the decimal string the API sends. */
export const formatQuantity = (value: number | string | null | undefined): string => {
  if (value === null || value === undefined || value === "") return "—";
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? quantityFormat.format(parsed) : String(value);
};

/** An amount in pesos. */
export const formatCurrency = (value: number | string): string =>
  new Intl.NumberFormat("en-US", { style: "currency", currency: "MXN" }).format(Number(value));

/** An ISO instant to local date and time. */
export const formatDateTime = (iso: string | null | undefined): string => {
  if (!iso) return "—";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? "—"
    : date.toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });
};

/** Truncates a long text for a table cell, without misrepresenting what is there. */
export const ellipsis = (value: string | null | undefined, max = 60): string => {
  if (!value) return "—";
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
};
