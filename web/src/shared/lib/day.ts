/**
 * The calendar day of a date in `YYYY-MM-DD` format, which is what the API expects in the
 * date filters by day (docs/api/convenciones.md §1.7). The **local** components of the date are used:
 * `toISOString()` would give the UTC day and, on the west coast of Mexico, the wrong day at night.
 */
export const toIsoDay = (date: Date | null | undefined): string | undefined => {
  if (!date || Number.isNaN(date.getTime())) return undefined;
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
};

/**
 * What the kit's date picker delivers: a `Date`, or a pair of them if it is a range. Here
 * only single dates exist, so any other form is treated as "no date".
 */
export const asDate = (value: unknown): Date | null => (value instanceof Date ? value : null);
