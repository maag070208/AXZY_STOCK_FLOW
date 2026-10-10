import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/types";
import { catalogApi } from "./api";
import type { Warehouse } from "./model";

interface WarehousesState {
  items: Warehouse[];
  loading: boolean;
  error: string | null;
}

/**
 * Las bodegas para un desplegable.
 *
 * Es un catálogo cerrado y diminuto (docs/api/convenciones.md §1.4), así que se
 * pide **completo y una sola vez** por pantalla, no paginado. El `alive` evita
 * escribir estado de un componente que ya se desmontó: sin él, cambiar de
 * pantalla mientras responde deja un aviso de React en la consola.
 */
export function useWarehouses(): WarehousesState {
  const [state, setState] = useState<WarehousesState>({ items: [], loading: true, error: null });

  useEffect(() => {
    let alive = true;
    catalogApi
      .warehouses()
      .then((response) => {
        if (alive) setState({ items: response.items, loading: false, error: null });
      })
      .catch((error: ApiError) => {
        if (alive) setState({ items: [], loading: false, error: error.message });
      });
    return () => {
      alive = false;
    };
  }, []);

  return state;
}
