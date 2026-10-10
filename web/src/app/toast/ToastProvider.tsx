import { ITToast } from "@axzydev/axzy_ui_system";
import type { ReactNode } from "react";
import { useDispatch, useSelector } from "react-redux";
import type { AppDispatch, RootState } from "@app/store";
import { dismissToast } from "./toast.slice";

/**
 * Only one notice at a time, mounted at the root.
 *
 * The `key` is the notice's `id`: without it, React reuses the previous component
 * and two messages in a row are seen as one that never goes away.
 */
export default function ToastProvider({ children }: { children: ReactNode }) {
  const toast = useSelector((state: RootState) => state.toast.current);
  const dispatch = useDispatch<AppDispatch>();

  return (
    <>
      {children}
      {toast && (
        <ITToast
          key={toast.id}
          message={toast.message}
          type={toast.type}
          duration={4000}
          position="top-right"
          onClose={() => dispatch(dismissToast())}
        />
      )}
    </>
  );
}
