import { ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { Navigate } from "react-router-dom";
import { LoginForm } from "@features/auth/login";
import { useSession } from "@shared/auth";

/**
 * Pantalla de acceso (1 de las 13 del legacy: `Login.tsx`).
 *
 * Si ya hay sesión confirmada se redirige a inicio: es el caso de recargar
 * estando dentro. Se decide con `status`, no con la mera presencia del token,
 * para no saltarse la verificación de `/auth/me`.
 */
export default function LoginPage() {
  const session = useSession();

  if (session.status === "authenticated" && session.user) {
    return <Navigate to="/" replace />;
  }

  return (
    <ITFlex
      as="div"
      direction="column"
      align="center"
      justify="center"
      gap={6}
      className="min-h-screen bg-slate-50 p-4"
    >
      <ITFlex direction="column" align="center" gap={6} className="w-full max-w-md">
        <LoginForm />
        <ITText as="p" muted className="text-[11px]">
          v{__APP_VERSION__}
        </ITText>
      </ITFlex>
    </ITFlex>
  );
}
