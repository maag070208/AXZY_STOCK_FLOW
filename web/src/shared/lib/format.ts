/**
 * Formatos de presentación. En español de México, que es el idioma de la
 * interfaz y de los mensajes de la API.
 */

const quantityFormat = new Intl.NumberFormat("es-MX", {
  minimumFractionDigits: 0,
  // La API manda la cantidad con 3 decimales (docs/api/convenciones.md §1.7).
  // Se muestran solo si existen: `5193`, no `5193.000`.
  maximumFractionDigits: 3,
});

/** Una cantidad del inventario. Acepta número o la cadena decimal que manda la API. */
export const formatQuantity = (value: number | string | null | undefined): string => {
  if (value === null || value === undefined || value === "") return "—";
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? quantityFormat.format(parsed) : String(value);
};

/** Un importe en pesos. */
export const formatCurrency = (value: number | string): string =>
  new Intl.NumberFormat("es-MX", { style: "currency", currency: "MXN" }).format(Number(value));

/** Un instante ISO a fecha y hora local. */
export const formatDateTime = (iso: string | null | undefined): string => {
  if (!iso) return "—";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? "—"
    : date.toLocaleString("es-MX", { dateStyle: "medium", timeStyle: "short" });
};

/** Corta un texto largo para una celda de tabla, sin mentir sobre lo que hay. */
export const ellipsis = (value: string | null | undefined, max = 60): string => {
  if (!value) return "—";
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
};
