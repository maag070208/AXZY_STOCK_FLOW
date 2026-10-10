/**
 * Bridge between the HTTP client and the store's session.
 *
 * The client lives in `shared/` and **cannot import from `app/` nor from
 * `entities/`** (layer rule). That is why it does not read the token from anywhere: the
 * session is registered by whoever can, `app/store.ts`, when it is built. It is the
 * same pattern that CYC uses, and it is what allows the client to be tested without mounting the
 * application.
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

/** After a renewal: the old access token already served and the refresh token rotated. */
export const updateSessionTokens = (tokens: { accessToken: string; refreshToken?: string }): void =>
  setTokens(tokens);

export const handleUnauthorized = (): void => onUnauthorized();
