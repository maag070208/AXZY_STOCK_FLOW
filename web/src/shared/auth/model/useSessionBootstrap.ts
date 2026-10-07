import { useEffect } from "react";
import { authApi } from "@shared/auth/api/authApi";
import { sessionStore } from "@shared/auth/model/sessionStore";

/**
 * Arranque de la sesión: confirma contra `GET /auth/me` que la sesión persistida
 * sigue viva y trae los permisos y alcances vigentes.
 *
 * Se hace **una sola vez al montar**. El repaso periódico al recuperar el foco
 * de la ventana queda pendiente para la Fase 5 avanzada, cuando exista la
 * consola de roles: hoy cambiar permisos exige recargar, que es aceptable para
 * una entrega sin usuarios reales.
 */
export const useSessionBootstrap = (): void => {
  useEffect(() => {
    // Sin token guardado no hay nada que confirmar: la app ya está en anónimo.
    if (!sessionStore.getAccessToken()) return;
    // StrictMode monta el provider dos veces en desarrollo; sin esta marca se
    // pediría `/auth/me` dos veces en cada arranque.
    if (sessionStore.isBootstrapping()) return;
    sessionStore.markBootstrapping(true);

    let cancelled = false;
    sessionStore.setLoading();

    authApi
      .me()
      .then((user) => {
        if (!cancelled) sessionStore.setUser(user);
      })
      .catch((error: { code?: string; details?: Record<string, unknown> }) => {
        // El `401` ya lo resolvió el cliente HTTP (renovó o cerró la sesión).
        // Aquí sólo se conserva el `code` para que el login explique qué pasó.
        if (!cancelled) sessionStore.setError(error?.code ?? "UNAUTHENTICATED", error?.details);
      })
      .finally(() => {
        sessionStore.markBootstrapping(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);
};
