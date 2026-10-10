import { useEffect } from "react";
import { useDispatch, useSelector } from "react-redux";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { meThunk } from "@entities/session";

/**
 * **Session-only** guard, without frame: it protects the account screens that
 * show full screen, as the password change does.
 *
 * Rule I8, as seen from the client, lives here: an account with the change
 * pending cannot go anywhere else. The API enforces it anyway (it responds
 * `PASSWORD_CHANGE_REQUIRED` for everything else), so this is so as not to take the
 * user to a screen that is going to reject them.
 */
export default function RequireAuth() {
  const dispatch = useDispatch<AppDispatch>();
  const token = useSelector((state: RootState) => state.auth.token);
  const user = useSelector((state: RootState) => state.auth.user);
  const location = useLocation();

  // When entering the screen directly (or reloading), the user is reread: the
  // permissions and the state of the pending change may have changed.
  useEffect(() => {
    if (token) void dispatch(meThunk());
  }, [token, dispatch]);

  if (!token) return <Navigate to="/login" replace state={{ from: location.pathname }} />;

  if (user?.mustChangePassword && location.pathname !== "/change-password") {
    return <Navigate to="/change-password" replace />;
  }

  return <Outlet />;
}
