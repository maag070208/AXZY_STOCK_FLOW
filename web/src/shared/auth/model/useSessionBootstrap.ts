import { useEffect } from "react";
import { authApi } from "@shared/auth/api/authApi";
import { sessionStore } from "@shared/auth/model/sessionStore";

/**
 * Arranque de la sesión: confirma contra `GET /auth/me` que la sesión persistida
 * sigue viva y trae los permisos y alcances vigentes.
 *
 * ── EL DEFECTO QUE TENÍA ESTO, Y POR QUÉ LA PRIMERA VERSIÓN NO VALÍA ────────
 *
 * La versión anterior hacía esto:
 *
 *     useEffect(() => {
 *       if (!sessionStore.getAccessToken()) return;
 *       if (sessionStore.isBootstrapping()) return;   // ← el segundo montaje sale
 *       sessionStore.markBootstrapping(true);
 *       let cancelled = false;                        // ← y la respuesta se descarta
 *       authApi.me().then((user) => { if (!cancelled) sessionStore.setUser(user); })
 *       …
 *       return () => { cancelled = true; };
 *     }, []);
 *
 * **En `StrictMode` —que es como arranca la web (`main.tsx`)— eso cuelga la
 * aplicación para siempre, y el síntoma no se parece a la causa:**
 *
 *  1. React monta el efecto, lo desmonta y lo vuelve a montar, para detectar
 *     efectos que no toleran el ciclo.
 *  2. El **primer** montaje lanza `/auth/me` y deja `bootstrapping = true`.
 *  3. El desmontaje pone `cancelled = true` en esa primera ejecución.
 *  4. El **segundo** montaje ve la marca puesta y **sale sin hacer nada**. No
 *     vuelve a pedir nada ni se suscribe a la respuesta en curso.
 *  5. Llega la respuesta del primero, ve `cancelled === true` y **no guarda el
 *     usuario**. El `finally` baja la marca, pero **ya no queda nadie que la lea**:
 *     el efecto no se vuelve a ejecutar.
 *
 * Resultado: `status` se queda en `"loading"`, `PrivateRoutes` pinta su marcador
 * (`<div aria-busy="true" />`) y **la pantalla sale en blanco**. Recargar en
 * cualquier ruta autenticada deja la aplicación inservible; navegar dentro de la
 * SPA no, porque el estado ya está en memoria y `/auth/me` no se repite.
 *
 * ── LA CORRECCIÓN: UNA SOLA PETICIÓN COMPARTIDA, NO UN «NO HAGAS NADA» ──────
 *
 * La marca que evitaba el `/auth/me` duplicado **no debía cancelar el trabajo**:
 * debía **compartirlo**. Ahora el arranque en vuelo vive a nivel de módulo y todo
 * el que llegue después **espera la misma promesa**. Así:
 *
 *   · `/auth/me` se pide **una sola vez**, que era el objetivo original.
 *   · Los dos montajes de `StrictMode` aplican el MISMO resultado, así que da
 *     igual cuál de ellos gane la carrera.
 *   · No hay `cancelled`: una promesa no se puede cancelar, y descartar el
 *     resultado no protegía de nada —el store es un único global de la app, no
 *     estado de un componente que pueda morir—.
 *
 * Si la promesa falla, se limpia para que un reintento posterior sea posible; un
 * fallo permanente dejaría la marca puesta y la app sin poder recuperarse.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** El arranque en vuelo. Uno solo para toda la aplicación. */
let bootstrapPromise: Promise<void> | null = null;

const runBootstrap = (): Promise<void> => {
  sessionStore.setLoading();

  return authApi
    .me()
    .then((user) => {
      sessionStore.setUser(user);
    })
    .catch((error: { code?: string; details?: Record<string, unknown> }) => {
      // El `401` ya lo resolvió el cliente HTTP (renovó el token o cerró la
      // sesión). Aquí solo se conserva el `code` para que el login explique qué
      // pasó, en lugar de dejar el estado en `loading` para siempre.
      sessionStore.setError(error?.code ?? "UNAUTHENTICATED", error?.details);
    })
    .finally(() => {
      bootstrapPromise = null;
    });
};

export const useSessionBootstrap = (): void => {
  useEffect(() => {
    // Sin token guardado no hay nada que confirmar: la app ya está en anónimo.
    if (!sessionStore.getAccessToken()) return;

    // Se comparte la petición en curso en lugar de salir sin hacer nada: salir
    // era justo lo que dejaba la aplicación colgada.
    bootstrapPromise ??= runBootstrap();
  }, []);
};
