import { ITLayout, type ITNavigationItem, type ITSidebarProps } from "@axzydev/axzy_ui_system";
import { useCallback, useEffect } from "react";
import glyph from "@shared/assets/brand/stockflow-glyph-light.svg";
import { useDispatch, useSelector } from "react-redux";
import { Navigate, Outlet, useLocation, useNavigate } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { MENU } from "@app/navigation";
import { can, logout, meThunk } from "@entities/session";
import { authApi } from "@entities/session/api";
import i18n from "@shared/i18n";

/**
 * The application frame: top bar, side menu and the content.
 *
 * The menu is built from **the permissions the API returns** (`/auth/me`): a
 * entry that cannot be seen is not drawn, and one typed by hand in the URL
 * the API rejects it anyway with `PERMISSION_DENIED`.
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
   * First the refresh token is revoked in the API —with the access token still
   * in force— and after that the local session is cleared. In reverse, the request would go
   * without a token and the session would stay alive on the server.
   */
  const handleLogout = useCallback(async () => {
    await authApi.logout(refreshToken).catch(() => undefined);
    dispatch(logout());
    navigate("/login", { replace: true });
  }, [dispatch, navigate, refreshToken]);

  if (!token) return <Navigate to="/login" replace />;

  // Temporary password: no navigation to any other place until it is changed (I8).
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
    // It hides itself, as in CYC: the content gains the width and the menu appears when the cursor approaches.
    // Collapsed shows only the icons; the name appears when expanded.
    isCollapsed: true,
    navigationItems,
  };

  const topBar = {
    // The light glyph: dark mode is fixed to `light` (app/main.tsx), and the
    // the brand package ships the dark variant for when it is enabled.
    // `alt` empty on purpose: the name goes next to it and repeating it bothers whoever
    // uses a screen reader.
    logo: <img src={glyph} alt="" className="h-10 w-auto" />,
    logoText: i18n.t("app.name"),
    userMenu: user
      ? {
          userName: user.name,
          userEmail: user.username,
          menuItems: [
            { label: i18n.t("common.changePassword"), onClick: () => navigate("/change-password") },
            { label: i18n.t("common.signOut"), onClick: () => void handleLogout() },
          ],
        }
      : undefined,
  };

  return (
    <ITLayout topBar={topBar} sidebar={sidebar} contentClassName="max-w-screen! m-0! !px-0">
      <Outlet />
    </ITLayout>
  );
}
