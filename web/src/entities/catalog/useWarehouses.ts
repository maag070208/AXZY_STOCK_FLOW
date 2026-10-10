import { useEffect, useState } from "react";
import { ApiError } from "@shared/api/types";
import { catalogApi } from "./api";
import type { Warehouse } from "./model";
import { describeError } from "@shared/api/errorMessage";

interface WarehousesState {
  items: Warehouse[];
  loading: boolean;
  error: string | null;
}

/**
 * The warehouses for a dropdown.
 *
 * It is a closed and tiny catalog (docs/api/convenciones.md §1.4), so it
 * requests **the whole list at once and only once** per screen, not paginated. The `alive` flag avoids
 * write a component's state after it has already unmounted: without it, switching
 * screen while it responds leaves a React warning in the console.
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
        if (alive) setState({ items: [], loading: false, error: describeError(error, "errors.unexpected") });
      });
    return () => {
      alive = false;
    };
  }, []);

  return state;
}
