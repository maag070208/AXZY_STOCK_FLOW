import { configureStore } from "@reduxjs/toolkit";
import { authReducer, logout, setTokens } from "@entities/session";
import { setSessionHooks } from "@shared/api/session";
import { toastReducer } from "./toast/toast.slice";

/**
 * The store. It is the **only** place that knows the session and the client at the same time
 * HTTP: `shared/` cannot import from `entities/` (layer rule), so the
 * client does not read the token from anywhere, and it is registered here.
 *
 * Thanks to this bridge, the HTTP client can be tested without mounting the
 * application, and a screen does not have to remember to send the `Authorization`.
 */
export const store = configureStore({
  reducer: {
    auth: authReducer,
    toast: toastReducer,
  },
});

setSessionHooks({
  token: () => store.getState().auth.token,
  refreshToken: () => store.getState().auth.refreshToken,
  onUnauthorized: () => store.dispatch(logout()),
  onTokens: (tokens) => store.dispatch(setTokens(tokens)),
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
