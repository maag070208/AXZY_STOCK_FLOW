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
 * Signs in. The error is returned **with its code** so that the screen
 * can tell "wrong password" apart from "account locked" without reading the
 * text, which changes (docs/api/errores.md §1.3).
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

/** Rereads the user and their permissions. Called on sign-in and after each role change. */
export const meThunk = createAsyncThunk("auth/me", async () => authApi.me());

/** Changes the password. The API revokes **all** sessions, including the current one. */
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
    /** Clears the local session. It does **not** call the API: the client has already decided it is no longer valid. */
    logout: () => initialState,

    /** After an HTTP client renewal: new tokens, same user. */
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
        // A `/me` that responds confirms that the token works and brings the current permissions
        // current: it is what makes removing a role take effect without signing in again.
        state.user = action.payload;
      })
      .addCase(meThunk.rejected, (state) => {
        // The HTTP client closes the session if the token is not valid; here it only
        // avoids leaving a stale user drawing the menu.
        state.user = null;
      });
  },
});

export const { logout, setTokens } = authSlice.actions;
export const authReducer = authSlice.reducer;

/** Does the session have this permission? It is for drawing; the decision belongs to the API. */
export const can = (user: { permissions: string[] } | null, permission: string): boolean =>
  user !== null && user.permissions.includes(permission);

/** One of them is enough. */
export const canAny = (user: { permissions: string[] } | null, permissions: readonly string[]): boolean =>
  permissions.some((permission) => can(user, permission));
