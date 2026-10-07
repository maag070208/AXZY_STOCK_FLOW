import { useSession, useSessionBootstrap } from "@shared/auth";
import { ToastProvider, useToast } from "@app/toast/ToastProvider";
import { useEffect, useRef, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { authorizationMessageKey } from "@shared/auth/lib/scopes";
import { AppShell } from "@widgets/app-shell";

/**
 * Composición de la aplicación: sesión, avisos y layout.
 *
 * El orden importa: `ToastProvider` envuelve al layout para que un aviso emitido
 * durante la navegación (por ejemplo al caer en `/login` por `TOKEN_REUSED`) no
 * se pierda cuando el layout se desmonte.
 */

/**
 * Traduce el `code` del contrato a un aviso y lo muestra una sola vez.
 *
 * `lastErrorCode` **no se limpia**: el `401` que cierra la sesión llega antes de
 * que el usuario vea la pantalla de login, así que el aviso tiene que poder
 * mostrarse después, aunque el login ya esté montado. El `ref` evita repetirlo.
 */
const SessionNotices = (): null => {
  const session = useSession();
  const show = useToast();
  const { t } = useTranslation(["common"]);
  const lastShown = useRef<string | null>(null);

  useEffect(() => {
    const code = session.lastErrorCode;
    if (!code || lastShown.current === code) return;
    lastShown.current = code;
    show({
      message: t(authorizationMessageKey(code)),
      type: code === "TOKEN_REUSED" ? "warning" : "error",
    });
  }, [session.lastErrorCode, show, t]);

  return null;
};

export const AppProviders = ({ children }: { children: ReactNode }): ReactNode => {
  // Confirmar la sesión persistida contra `/auth/me` antes de decidir rutas.
  useSessionBootstrap();

  return (
    <ToastProvider>
      <SessionNotices />
      <AppShell>{children}</AppShell>
    </ToastProvider>
  );
};
