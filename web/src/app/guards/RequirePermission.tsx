import type { ReactNode } from "react";
import { useSelector } from "react-redux";
import { Navigate } from "react-router-dom";
import type { RootState } from "@app/store";
import { canAny } from "@entities/session";

interface Props {
  /** Basta con tener uno de estos permisos. */
  permissions: readonly string[];
  children: ReactNode;
}

/**
 * Puerta de permiso a nivel de ruta.
 *
 * Es **para no ofrecer lo que va a fallar**, no una defensa: la API decide con
 * `AccessDecider` en cada petición (docs/arquitectura/api-spring.md §6) y
 * responde `403` aunque alguien escriba la URL a mano.
 *
 * Mientras `/auth/me` está en vuelo no se decide nada: redirigir en falso al
 * recargar la página echaría al usuario de una pantalla que sí puede ver.
 */
export default function RequirePermission({ permissions, children }: Props) {
  const user = useSelector((state: RootState) => state.auth.user);

  if (!user) return null;
  if (!canAny(user, permissions)) return <Navigate to="/" replace />;

  return <>{children}</>;
}
