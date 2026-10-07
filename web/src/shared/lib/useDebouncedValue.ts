import { useEffect, useState } from "react";

/**
 * Devuelve el valor «estabilizado»: sólo cambia tras `delay` ms sin cambios.
 * Se usa para el buscador de listados, que dispara una petición por pulsación
 * contra 1.398 productos.
 */
export const useDebouncedValue = <T,>(value: T, delay = 400): T => {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timer);
  }, [value, delay]);

  return debounced;
};
