import { createSlice, type PayloadAction } from "@reduxjs/toolkit";

export type ToastType = "success" | "error" | "warning" | "info";

export interface Toast {
  /** Cambia en cada aviso: es lo que hace que dos iguales seguidos se muestren dos veces. */
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
      /** `showToast("Guardado")` sin construir el tipo a mano en cada pantalla. */
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
