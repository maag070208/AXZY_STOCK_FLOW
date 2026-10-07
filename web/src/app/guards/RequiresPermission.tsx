import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useCan } from "@entities/access";
import { useSession, type Permission } from "@shared/auth";

/**
 * Guard de nivel 2: **¿la sesión tiene el permiso de esta URL?**
 *
 * Bloquea el acceso directo por URL a una sección que el menú no muestra.
 * Mientras `/auth/me` esté en vuelo no se decide nada (devolver `null`), porque
 * el usuario persistido puede venir con permisos provisionales.
 *
 * Se declara con `children` para poder envolver rutas sin cambiar su JSX; el
 * nombre del archivo es singular y el del componente plural a propósito: es
 * «RequiresPermission» lo que se lee en la ruta.
 */
const RequiresPermission = ({
  permission,
  children,
}: {
  permission: Permission;
  children: ReactNode;
}): ReactNode => {
  const session = useSession();
  const can = useCan(permission);

  if (session.status === "loading" || !session.user) return null;
  if (!can) return <Navigate to="/" replace />;

  return <>{children}</>;
};

export default RequiresPermission;
