import { createAsyncThunk, createSlice, type PayloadAction } from "@reduxjs/toolkit";
import { ApiError } from "@shared/api/types";
import { authApi } from "./api";
import type { AuthSession, AuthState } from "./model";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

const initialState: AuthState = {
  token: null,
  refreshToken: null,
  user: null,
  status: "idle",
  error: null,
};

/**
 * Inicia sesión. El error se devuelve **con su código** para que la pantalla
 * pueda distinguir «contraseña incorrecta» de «cuenta bloqueada» sin leer el
 * texto, que cambia (docs/api/errores.md §1.3).
 */
export const loginThunk = createAsyncThunk<
  AuthSession,
  { username: string; password: string },
  { rejectValue: { code: string; message: string } }
>("auth/login", async ({ username, password }, { rejectWithValue }) => {
  try {
    return await authApi.login(username, password);
  } catch (error) {
    const apiError = error as ApiError;
    return rejectWithValue({ code: apiError.code ?? "UNKNOWN", message: describeError(apiError, "auth.loginFailed") });
  }
});

/** Relee al usuario y sus permisos. Se llama al entrar y tras cada cambio de rol. */
export const meThunk = createAsyncThunk("auth/me", async () => authApi.me());

/** Cambia la contraseña. La API revoca **todas** las sesiones, incluida la actual. */
export const changePasswordThunk = createAsyncThunk<
  void,
  { currentPassword: string; newPassword: string },
  { rejectValue: { code: string; message: string } }
>("auth/changePassword", async ({ currentPassword, newPassword }, { rejectWithValue }) => {
  try {
    await authApi.changePassword(currentPassword, newPassword);
  } catch (error) {
    const apiError = error as ApiError;
    return rejectWithValue({ code: apiError.code ?? "UNKNOWN", message: describeError(apiError, "auth.changePasswordFailed") });
  }
});

const authSlice = createSlice({
  name: "auth",
  initialState,
  reducers: {
    /** Limpia la sesión local. **No** llama a la API: el cliente ya decidió que no sirve. */
    logout: () => initialState,

    /** Tras una renovación del cliente HTTP: tokens nuevos, mismo usuario. */
    setTokens: (state, action: PayloadAction<{ accessToken: string; refreshToken?: string }>) => {
      state.token = action.payload.accessToken;
      if (action.payload.refreshToken) state.refreshToken = action.payload.refreshToken;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(loginThunk.pending, (state) => {
        state.status = "loading";
        state.error = null;
      })
      .addCase(loginThunk.fulfilled, (state, action) => {
        state.status = "idle";
        state.token = action.payload.accessToken;
        state.refreshToken = action.payload.refreshToken;
        state.user = action.payload.user;
      })
      .addCase(loginThunk.rejected, (state, action) => {
        state.status = "error";
        state.error = action.payload?.message ?? i18n.t("auth.loginFailed");
      })
      .addCase(meThunk.fulfilled, (state, action) => {
        // Un `/me` que responde confirma que el token sirve y trae los permisos
        // vigentes: es lo que hace que quitar un rol surta efecto sin volver a entrar.
        state.user = action.payload;
      })
      .addCase(meThunk.rejected, (state) => {
        // El cliente HTTP cierra la sesión si el token no vale; aquí solo se
        // evita dejar un usuario viejo dibujando el menú.
        state.user = null;
      });
  },
});

export const { logout, setTokens } = authSlice.actions;
export const authReducer = authSlice.reducer;

/** ¿La sesión tiene este permiso? Es para dibujar; la decisión es de la API. */
export const can = (user: { permissions: string[] } | null, permission: string): boolean =>
  user !== null && user.permissions.includes(permission);

/** Basta con uno de ellos. */
export const canAny = (user: { permissions: string[] } | null, permissions: readonly string[]): boolean =>
  permissions.some((permission) => can(user, permission));
