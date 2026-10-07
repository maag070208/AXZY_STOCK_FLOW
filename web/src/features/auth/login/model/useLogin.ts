import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@shared/api";
import { authApi } from "@shared/auth";
import { sessionStore } from "@shared/auth/model/sessionStore";

/**
 * Inicio de sesión.
 *
 * La decisión de qué decirle al usuario se toma **con el `code`** (§1.3), no con
 * el mensaje: la UI traduce `code → texto`. El caso que más importa es
 * `ACCOUNT_LOCKED`, que trae `details.until`; el legacy mostraba «no autorizado»
 * y el usuario volvía a teclear la contraseña hasta agotar los intentos.
 */
export interface LoginState {
  submitting: boolean;
  /** `code` del contrato del último intento fallido, o `null`. */
  errorCode: string | null;
  /** `details` del error, p. ej. `{ until }` de `ACCOUNT_LOCKED`. */
  errorDetails: Record<string, unknown> | null;
  submit: (username: string, password: string) => Promise<void>;
  reset: () => void;
}

export const useLogin = (): LoginState => {
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [errorDetails, setErrorDetails] = useState<Record<string, unknown> | null>(null);

  const submit = useCallback(
    async (username: string, password: string) => {
      setSubmitting(true);
      setErrorCode(null);
      setErrorDetails(null);
      try {
        const data = await authApi.login({ username, password });
        // El store persiste los tokens antes de navegar: si se navegara primero,
        // el guard leería una sesión vacía y rebotaría a `/login` otra vez.
        sessionStore.start(data);
        navigate("/", { replace: true });
      } catch (error) {
        const apiError = error instanceof ApiError ? error : null;
        setErrorCode(apiError?.code ?? "UNKNOWN");
        setErrorDetails(apiError?.details ?? null);
      } finally {
        setSubmitting(false);
      }
    },
    [navigate]
  );

  const reset = useCallback(() => {
    setErrorCode(null);
    setErrorDetails(null);
  }, []);

  return { submitting, errorCode, errorDetails, submit, reset };
};
