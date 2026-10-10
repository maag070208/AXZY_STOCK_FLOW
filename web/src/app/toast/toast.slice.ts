import { createSlice, type PayloadAction } from "@reduxjs/toolkit";

export type ToastType = "success" | "error" | "warning" | "info";

export interface Toast {
  /** Changes on each notice: it is what makes two identical notices in a row show up twice. */
  id: number;
  message: string;
  type: ToastType;
}

interface ToastState {
  current: Toast | null;
}

const initialState: ToastState = { current: null };

const toastSlice = createSlice({
  name: "toast",
  initialState,
  reducers: {
    showToast: {
      reducer: (state, action: PayloadAction<Toast>) => {
        state.current = action.payload;
      },
      /** `showToast("Saved")` so the type is not built by hand on each screen. */
      prepare: (message: string, type: ToastType = "success") => ({
        payload: { id: Date.now(), message, type },
      }),
    },
    dismissToast: (state) => {
      state.current = null;
    },
  },
});

export const { showToast, dismissToast } = toastSlice.actions;
export const toastReducer = toastSlice.reducer;
