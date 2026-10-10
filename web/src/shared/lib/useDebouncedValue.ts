import { useEffect, useState } from "react";

/**
 * A value that only propagates when it stops changing.
 *
 * It is what prevents the free-text search from firing one request per keystroke: the
 * user types and the query is sent when it stops. The delay is the same one the
 * the kit for its column filters (400 ms), so that they feel the same.
 */
export function useDebouncedValue<T>(value: T, delayMs = 400): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
