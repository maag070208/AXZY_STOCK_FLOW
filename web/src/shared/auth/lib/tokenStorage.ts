/**
 * Persistencia de la sesión.
 *
 * **`localStorage` por decisión explícita**, no `sessionStorage`: el turno de
 * noche recarga la web a media operación y perder la sesión obliga a volver a
 * teclear credenciales en una terminal de piso. El refresh token se rota en cada
 * uso (§2 del contrato): una copia robada del almacenamiento sirve una sola vez
 * y el reuso se detecta como `TOKEN_REUSED`, que revoca toda la familia.
 *
 * La clave lleva versión (`_v1`) para poder cambiar la forma de lo guardado sin
 * que una sesión vieja rompa el arranque.
 */
const STORAGE_KEY = "almacen_integral_auth_v1";

export interface PersistedSession {
  accessToken: string;
  refreshToken: string;
  userId: number | null;
}

export const emptySession = (): PersistedSession => ({
  accessToken: "",
  refreshToken: "",
  userId: null,
});

export const readSession = (): PersistedSession => {
  if (typeof window === "undefined") return emptySession();
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return emptySession();
    const parsed = JSON.parse(raw) as Partial<PersistedSession>;
    return {
      accessToken: parsed.accessToken ?? "",
      refreshToken: parsed.refreshToken ?? "",
      userId: parsed.userId ?? null,
    };
  } catch {
    // Almacenamiento corrupto: se descarta en lugar de tumbar el arranque.
    return emptySession();
  }
};

export const writeSession = (session: Partial<PersistedSession>): PersistedSession => {
  const next: PersistedSession = { ...readSession(), ...session };
  if (typeof window !== "undefined") {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      /* modo privado o cuota llena: la sesión sigue viva en memoria */
    }
  }
  return next;
};

export const clearStoredSession = (): void => {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* nada que hacer */
  }
};
