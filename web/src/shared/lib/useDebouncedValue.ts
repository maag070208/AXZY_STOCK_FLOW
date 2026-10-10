import { useEffect, useState } from "react";

/**
 * Un valor que solo se propaga cuando deja de cambiar.
 *
 * Es lo que evita que la búsqueda libre dispare una petición por tecla: el
 * usuario escribe y la consulta sale cuando para. El retardo es el mismo que usa
 * el kit para sus filtros de columna (400 ms), para que se sientan igual.
 */
export function useDebouncedValue<T>(value: T, delayMs = 400): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
