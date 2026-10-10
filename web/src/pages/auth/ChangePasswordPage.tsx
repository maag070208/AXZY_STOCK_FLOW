import { ITCard, ITText } from "@axzydev/axzy_ui_system";
import mark from "@shared/assets/brand/stockflow-icon-light.svg";
import ChangePasswordForm from "@features/auth/ChangePasswordForm";
import i18n from "@shared/i18n";

/**
 * Password change in full screen, **without menu**: it is the only screen that
 * exists as long as the password is temporary (I8).
 */
export default function ChangePasswordPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F2F6FC] px-4">
      <ITCard className="w-full max-w-md p-6">
        <div className="mb-4 flex flex-col items-center gap-1">
          <img src={mark} alt={i18n.t("app.name")} className="h-20 w-20" />
          <span className="text-2xl font-semibold text-[#16315F]">{i18n.t("app.name")}</span>
          <ITText muted className="text-xs">
            Cambio de contraseña
          </ITText>
        </div>
        <ChangePasswordForm />
      </ITCard>
    </div>
  );
}
