import { ITToast } from "@axzydev/axzy_ui_system";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

/**
 * Avisos efímeros de la aplicación.
 *
 * Se reutiliza `ITToast` del UI kit y se resuelve el estado con contexto en vez
 * de con un slice de Redux: el único estado global de la app es la sesión, y un
 * store para «un mensaje y un temporizador» es ceremonia sin beneficio.
 *
 * `ToastProvider` va **dentro** del `HashRouter` y por fuera del layout: así el
 * aviso sobrevive a la navegación (p. ej. «tu sesión se cerró por seguridad»
 * cuando el usuario cae en `/login`).
 */
export type ToastType = "success" | "error" | "warning" | "info" | "primary" | "danger";

export type ToastPosition =
  | "top-right"
  | "top-center"
  | "top-left"
  | "bottom-right"
  | "bottom-center"
  | "bottom-left";

export interface ToastOptions {
  message: string;
  type?: ToastType;
  duration?: number;
  position?: ToastPosition;
}

type ShowToast = (options: ToastOptions) => void;

const ToastContext = createContext<ShowToast | null>(null);

export const ToastProvider = ({ children }: { children: ReactNode }): ReactNode => {
  const [toast, setToast] = useState<Required<Omit<ToastOptions, "message">> & { message: string } | null>(
    null
  );
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const show = useCallback<ShowToast>((options) => {
    if (timer.current) clearTimeout(timer.current);
    setToast({
      message: options.message,
      type: options.type ?? "info",
      // Los errores se quedan más tiempo: el usuario tiene que poder leerlos.
      duration: options.duration ?? (options.type === "error" ? 5000 : 2500),
      position: options.position ?? "bottom-center",
    });
  }, []);

  useEffect(() => {
    if (!toast) return undefined;
    timer.current = setTimeout(() => setToast(null), toast.duration);
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, [toast]);

  const value = useMemo(() => show, [show]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      {toast && (
        <ITToast
          message={toast.message}
          type={toast.type}
          duration={toast.duration}
          position={toast.position}
          onClose={() => setToast(null)}
        />
      )}
    </ToastContext.Provider>
  );
};

/** Muestra un aviso. Falla si falta el provider: es un error de montaje. */
export const useToast = (): ShowToast => {
  const show = useContext(ToastContext);
  if (!show) throw new Error("useToast requiere <ToastProvider> en la raíz de la app");
  return show;
};
