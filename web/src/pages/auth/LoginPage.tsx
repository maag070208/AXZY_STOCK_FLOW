import AuthLayout from "@shared/ui/auth-layout/AuthLayout";
import LoginForm from "@features/auth/LoginForm";

/** The sign-in screen: the access frame with the brand animation and the form. */
export default function LoginPage() {
  return (
    <AuthLayout>
      <LoginForm />
    </AuthLayout>
  );
}
