import { useEffect } from "react";
import { useDispatch, useSelector } from "react-redux";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { meThunk } from "@entities/session";

/**
 * Guard de **solo sesión**, sin armazón: protege las pantallas de cuenta que se
 * muestran a pantalla completa, como el cambio de contraseña.
 *
 * Aquí vive la regla I8 vista desde el cliente: una cuenta con el cambio
 * pendiente no puede ir a ninguna otra parte. La API lo impone igualmente (responde
 * `PASSWORD_CHANGE_REQUIRED` a todo lo demás), así que esto es para no llevar al
 * usuario a una pantalla que va a rechazarle.
 */
export default function RequireAuth() {
  const dispatch = useDispatch<AppDispatch>();
  const token = useSelector((state: RootState) => state.auth.token);
  const user = useSelector((state: RootState) => state.auth.user);
  const location = useLocation();

  // Al entrar directo a la pantalla (o al recargar) se relee quién es: los
  // permisos y el estado del cambio pendiente pueden haber cambiado.
  useEffect(() => {
    if (token) void dispatch(meThunk());
  }, [token, dispatch]);

  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname }} />;

  if (user?.mustChangePassword && location.pathname !== "/change-password") {
    return <Navigate to="/change-password" replace />;
  }

  return <Outlet />;
}
