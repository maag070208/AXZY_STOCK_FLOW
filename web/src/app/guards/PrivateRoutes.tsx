import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useSession } from "@shared/auth";

/**
 * Guard de nivel 1: **¿hay sesión?**
 *
 * Tres estados que no se pueden confundir:
 * - `loading` (con token guardado pero sin `/auth/me` resuelto): se pinta un
 *   marcador y **no se redirige**. Redirigir aquí es el error clásico: al
 *   recargar en `/invoices` el usuario cae en el login y cree que lo sacaron.
 * - `anonymous`: no hay sesión → al login, recordando a dónde iba.
 * - `authenticated`: la sesión está confirmada → se pinta la pantalla.
 *
 * El backend revalida siempre: este guard es de navegación, no de seguridad.
 */
const PrivateRoutes = ({ children }: { children: ReactNode }): ReactNode => {
  const session = useSession();

  if (session.status === "loading") {
    // Marcador neutro: evita el parpadeo al login sin inventar un spinner.
    return <div style={{ minHeight: "100vh" }} aria-busy="true" />;
  }

  if (session.status === "anonymous" || !session.user) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
};

export default PrivateRoutes;
