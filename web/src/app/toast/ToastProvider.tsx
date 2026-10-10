import { ITToast } from "@axzydev/axzy_ui_system";
import type { ReactNode } from "react";
import { useDispatch, useSelector } from "react-redux";
import type { AppDispatch, RootState } from "@app/store";
import { dismissToast } from "./toast.slice";

/**
 * Un solo aviso a la vez, montado en la raíz.
 *
 * El `key` es el `id` del aviso: sin él, React reutiliza el componente anterior
 * y dos mensajes seguidos se ven como uno que nunca desaparece.
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
