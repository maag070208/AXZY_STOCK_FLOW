import { ITCard, ITText } from "@axzydev/axzy_ui_system";
import mark from "@shared/assets/brand/stockflow-icon-light.svg";
import LoginForm from "@features/auth/LoginForm";

/** La entrada: una tarjeta centrada, sin armazón ni menú. */
export default function LoginPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F2F6FC] px-4">
      <ITCard className="w-full max-w-sm p-6">
        <div className="mb-4 flex flex-col items-center gap-1">
          <img src={mark} alt="StockFlow" className="h-20 w-20" />
          <span className="text-2xl font-semibold text-[#16315F]">StockFlow</span>
          <ITText muted className="text-xs">
            Gestión de almacén
          </ITText>
        </div>
        <LoginForm />
      </ITCard>
    </div>
  );
}
