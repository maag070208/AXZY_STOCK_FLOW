import AuthLayout from "@shared/ui/auth-layout/AuthLayout";
import LoginForm from "@features/auth/LoginForm";

/** La entrada: el marco de acceso con la animación de marca y el formulario. */
export default function LoginPage() {
  return (
    <AuthLayout>
      <LoginForm />
    </AuthLayout>
  );
}
