import type { Page } from "@shared/api";

/** Página vacía, con el tamaño pedido, para estados iniciales y listas sin datos. */
export const emptyPage = <T>(size = 50): Page<T> => ({
  items: [],
  page: 0,
  size,
  totalItems: 0,
  totalPages: 0,
});
