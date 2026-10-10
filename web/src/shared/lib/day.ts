/**
 * El día de calendario de una fecha en formato `AAAA-MM-DD`, que es lo que la API espera en los
 * filtros por día (docs/api/convenciones.md §1.7). Se usan los componentes **locales** de la fecha:
 * `toISOString()` daría el día UTC y, en la costa oeste de México, el día equivocado de noche.
 */
export const toIsoDay = (date: Date | null | undefined): string | undefined => {
  if (!date || Number.isNaN(date.getTime())) return undefined;
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

/**
 * Lo que entrega el selector de fecha del kit: una `Date`, o un par de ellas si es de rango. Aquí
 * solo hay fechas sueltas, así que cualquier otra forma se trata como «sin fecha».
 */
export const asDate = (value: unknown): Date | null => (value instanceof Date ? value : null);
