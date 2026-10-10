import { ITLayout, type ITNavigationItem, type ITSidebarProps } from "@axzydev/axzy_ui_system";
import { useCallback, useEffect, type ReactNode } from "react";
import { FaBoxes, FaHome, FaLayerGroup, FaWarehouse } from "react-icons/fa";
import glyph from "@shared/assets/brand/stockflow-glyph-light.svg";
import { useDispatch, useSelector } from "react-redux";
import { Navigate, Outlet, useLocation, useNavigate } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { can, logout, meThunk } from "@entities/session";
import { authApi } from "@entities/session/api";

/** El menú, con el permiso que exige cada entrada. La web no reimplementa la matriz. */
const MENU: { id: string; label: string; path: string; permission: string | null; icon: ReactNode }[] = [
  { id: "home", label: "Inicio", path: "/", permission: null, icon: <FaHome size={14} /> },
  { id: "products", label: "Productos", path: "/products", permission: "product.view", icon: <FaBoxes size={14} /> },
  {
    id: "locations",
    label: "Ubicaciones",
    path: "/locations",
    permission: "location.view",
    icon: <FaLayerGroup size={14} />,
  },
  {
    id: "warehouses",
    label: "Bodegas",
    path: "/warehouses",
    permission: "warehouse.view",
    icon: <FaWarehouse size={14} />,
  },
];

/**
 * El armazón de la aplicación: barra superior, menú lateral y el contenido.
 *
 * El menú se arma con **los permisos que devuelve la API** (`/auth/me`): una
 * entrada que no se puede ver no se dibuja, y una que se escriba a mano en la URL
 * la rechaza la API de todos modos con `PERMISSION_DENIED`.
 */
export default function PrivateRoutes() {
  const dispatch = useDispatch<AppDispatch>();
  const navigate = useNavigate();
  const location = useLocation();
  const { token, refreshToken, user } = useSelector((state: RootState) => state.auth);

  useEffect(() => {
    if (token) void dispatch(meThunk());
  }, [token, dispatch]);

  /**
   * Primero se revoca el refresh token en la API —con el acceso todavía
   * vigente— y después se limpia la sesión local. Al revés, la petición saldría
   * sin token y la sesión quedaría viva en el servidor.
   */
  const handleLogout = useCallback(async () => {
    await authApi.logout(refreshToken).catch(() => undefined);
    dispatch(logout());
    navigate("/login", { replace: true });
  }, [dispatch, navigate, refreshToken]);

  if (!token) return <Navigate to="/login" replace />;

  // Contraseña temporal: no se navega a ningún otro sitio hasta cambiarla (I8).
  if (user?.mustChangePassword) return <Navigate to="/change-password" replace />;

  const navigationItems: ITNavigationItem[] = MENU.filter(
    (entry) => entry.permission === null || can(user, entry.permission),
  ).map((entry) => ({
    id: entry.id,
    label: entry.label,
    icon: entry.icon,
    action: () => navigate(entry.path),
    isActive: entry.path === "/" ? location.pathname === "/" : location.pathname.startsWith(entry.path),
  }));

  const sidebar: ITSidebarProps = {
    // Desplegado: son cuatro entradas y el nombre es lo que las hace legibles.
    isCollapsed: false,
    navigationItems,
  };

  const topBar = {
    // El glifo claro: el modo oscuro está fijado en `light` (app/main.tsx), y el
    // paquete de marca trae la variante oscura para cuando se active.
    // `alt` vacío a propósito: el nombre va al lado y repetirlo molesta a quien
    // usa un lector de pantalla.
    logo: <img src={glyph} alt="" className="h-10 w-auto" />,
    logoText: "StockFlow",
    userMenu: user
      ? {
          userName: user.name,
          userEmail: user.username,
          menuItems: [
            { label: "Cambiar contraseña", onClick: () => navigate("/change-password") },
            { label: "Cerrar sesión", onClick: () => void handleLogout() },
          ],
        }
      : undefined,
  };

  return (
    <ITLayout topBar={topBar} sidebar={sidebar}>
      <Outlet />
    </ITLayout>
  );
}
