/**
 * Puente entre el cliente HTTP y la sesión.
 *
 * El cliente vive en `shared` y no puede conocer el store de `app` (regla FSD
 * de `eslint.config.js`), así que aquí se declaran *ganchos* que `app/store.ts`
 * rellena al arrancar. Es el mismo patrón que usa el repo de referencia y evita
 * el import circular `client → store → client`.
 */
let getToken: () => string | null = () => null;
let getRefreshToken: () => string | null = () => null;
let setTokens: (tokens: { accessToken: string; refreshToken?: string }) => void = () => {};
let onUnauthorized: () => void = () => {};
let onTokensRevoked: (code: string) => void = () => {};

export const setSessionHooks = (hooks: {
  tokenGetter: () => string | null;
  unauthorizedHandler: () => void;
  refreshTokenGetter?: () => string | null;
  tokensSetter?: (tokens: { accessToken: string; refreshToken?: string }) => void;
  /** Se llama cuando el refresh responde `TOKEN_REUSED`: la familia quedó revocada. */
  tokensRevokedHandler?: (code: string) => void;
}): void => {
  getToken = hooks.tokenGetter;
  onUnauthorized = hooks.unauthorizedHandler;
  if (hooks.refreshTokenGetter) getRefreshToken = hooks.refreshTokenGetter;
  if (hooks.tokensSetter) setTokens = hooks.tokensSetter;
  if (hooks.tokensRevokedHandler) onTokensRevoked = hooks.tokensRevokedHandler;
};

export const getSessionToken = (): string | null => getToken();
export const getSessionRefreshToken = (): string | null => getRefreshToken();

/** Publica los tokens nuevos (rotación del refresh, §2 del contrato). */
export const updateSessionTokens = (tokens: {
  accessToken: string;
  refreshToken?: string;
}): void => setTokens(tokens);

export const handleUnauthorized = (): void => onUnauthorized();

/** El servidor detectó reuso de un refresh token y revocó toda la familia. */
export const handleTokensRevoked = (code: string): void => onTokensRevoked(code);
