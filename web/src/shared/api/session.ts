/**
 * Puente entre el cliente HTTP y la sesión del store.
 *
 * El cliente vive en `shared/` y **no puede importar de `app/` ni de
 * `entities/`** (regla de capas). Por eso no lee el token de ningún sitio: la
 * sesión se la registra quien sí puede, `app/store.ts`, al construirse. Es el
 * mismo patrón que usa CYC, y es lo que permite probar el cliente sin montar la
 * aplicación.
 */

let getToken: () => string | null = () => null;
let getRefreshToken: () => string | null = () => null;
let setTokens: (tokens: { accessToken: string; refreshToken?: string }) => void = () => {};
let onUnauthorized: () => void = () => {};

export const setSessionHooks = (hooks: {
  token: () => string | null;
  refreshToken: () => string | null;
  onUnauthorized: () => void;
  onTokens: (tokens: { accessToken: string; refreshToken?: string }) => void;
}): void => {
  getToken = hooks.token;
  getRefreshToken = hooks.refreshToken;
  onUnauthorized = hooks.onUnauthorized;
  setTokens = hooks.onTokens;
};

export const getSessionToken = (): string | null => getToken();

export const getSessionRefreshToken = (): string | null => getRefreshToken();

/** Tras una renovación: el access viejo ya sirvió y el refresh rotó. */
export const updateSessionTokens = (tokens: { accessToken: string; refreshToken?: string }): void =>
  setTokens(tokens);

export const handleUnauthorized = (): void => onUnauthorized();
