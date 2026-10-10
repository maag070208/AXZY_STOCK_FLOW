import { ITAlert, ITButton, ITFlex, ITInput } from "@axzydev/axzy_ui_system";
import { useState, type FormEvent } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { changePasswordThunk, logout } from "@entities/session";
import { showToast } from "@app/toast/toast.slice";
import i18n from "@shared/i18n";

/**
 * Password change, mandatory the first time.
 *
 * **The session is closed and you log back in at the end.** It is not a decision of
 * the web: the API revokes **all** sessions when the password changes, including the
 * the current one, because whoever changes it usually suspects it has been compromised
 * (`ChangePassword`, api/application/.../identity/SessionCommands.kt). Staying on
 * the screen with a token that can no longer be renewed would give a confusing error some
 * minutes later.
 */
export default function ChangePasswordForm() {
  const dispatch = useDispatch<AppDispatch>();
  const navigate = useNavigate();
  const user = useSelector((state: RootState) => state.auth.user);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [repeat, setRepeat] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);

    // The only local check: that the two writes match. The policy
    // of the password is enforced by the API (`WEAK_PASSWORD`), and duplicating it here
    // would guarantee that the two drift apart over time.
    if (newPassword !== repeat) {
      setError(i18n.t("auth.passwordsDoNotMatch"));
      return;
    }

    setSending(true);
    const result = await dispatch(changePasswordThunk({ currentPassword, newPassword }));
    setSending(false);

    if (changePasswordThunk.rejected.match(result)) {
      setError(result.payload?.message ?? i18n.t("auth.changePasswordFailed"));
      return;
    }

    dispatch(showToast(i18n.t("auth.passwordChanged"), "success"));
    dispatch(logout());
    navigate("/login", { replace: true });
  };

  return (
    <form onSubmit={submit} className="w-full">
      <ITFlex direction="column" gap={4}>
        {user?.mustChangePassword && (
          <ITAlert variant="warning" title={i18n.t("auth.temporaryPassword")}>
            Tu contraseña es temporal. Cámbiala para poder usar el sistema.
          </ITAlert>
        )}

        <ITInput
          name="currentPassword"
          label={i18n.t("auth.currentPassword")}
          type="password"
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          required
          autoFocus
        />
        <ITInput
          name="newPassword"
          label={i18n.t("auth.newPassword")}
          type="password"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          required
        />
        <ITInput
          name="repeat"
          label={i18n.t("auth.repeatNewPassword")}
          type="password"
          value={repeat}
          onChange={(event) => setRepeat(event.target.value)}
          required
        />

        {error && <ITAlert variant="error" title={i18n.t("auth.changePasswordFailedShort")}>{error}</ITAlert>}

        <ITButton
          type="submit"
          label={sending ? i18n.t("auth.changing") : i18n.t("common.changePassword")}
          color="primary"
          disabled={sending}
        />
      </ITFlex>
    </form>
  );
}
