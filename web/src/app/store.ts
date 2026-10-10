import { configureStore } from "@reduxjs/toolkit";
import { authReducer, logout, setTokens } from "@entities/session";
import { setSessionHooks } from "@shared/api/session";
import { toastReducer } from "./toast/toast.slice";

/**
 * El almacén. Es el **único** sitio que conoce a la vez la sesión y el cliente
 * HTTP: `shared/` no puede importar de `entities/` (regla de capas), así que el
 * cliente no lee el token de ningún sitio y se lo registran aquí.
 *
 * Gracias a este puente, el cliente HTTP se puede probar sin montar la
 * aplicación, y una pantalla no tiene que acordarse de mandar el `Authorization`.
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
