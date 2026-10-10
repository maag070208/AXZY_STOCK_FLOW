import type { ReactNode } from "react";
import { useSelector } from "react-redux";
import { Navigate } from "react-router-dom";
import type { RootState } from "@app/store";
import { canAny } from "@entities/session";

interface Props {
  /** Having at least one of these permissions is enough. */
  permissions: readonly string[];
  children: ReactNode;
}

/**
 * Permission gate at the route level.
 *
 * It is **to avoid offering what will fail**, not a defense: the API decides with
 * `AccessDecider` on each request (docs/arquitectura/api-spring.md §6) and
 * responds `403` even if someone types the URL by hand.
 *
 * While `/auth/me` is in flight nothing is decided: redirecting by mistake to
 * reloading the page would kick the user out of a screen they can see.
 */
export default function RequirePermission({ permissions, children }: Props) {
  const user = useSelector((state: RootState) => state.auth.user);

  if (!user) return null;
  if (!canAny(user, permissions)) return <Navigate to="/" replace />;

  return <>{children}</>;
}
