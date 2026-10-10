import { ITAlert, ITButton, ITFlex, ITInput } from "@axzydev/axzy_ui_system";
import { useState, type FormEvent } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { changePasswordThunk, logout } from "@entities/session";
import { showToast } from "@app/toast/toast.slice";

/**
 * Cambio de contraseña, obligatorio la primera vez.
 *
 * **Al terminar se cierra la sesión y se vuelve a entrar.** No es una decisión de
 * la web: la API revoca **todas** las sesiones al cambiar la contraseña, incluida
 * la actual, porque quien la cambia suele sospechar que está comprometida
 * (`ChangePassword`, api/application/.../identity/SessionCommands.kt). Quedarse en
 * la pantalla con un token que ya no se puede renovar daría un error confuso unos
 * minutos después.
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

    // La única comprobación local: que las dos escrituras coincidan. La política
    // de la contraseña la aplica la API (`WEAK_PASSWORD`), y duplicarla aquí
    // garantizaría que las dos se separen con el tiempo.
    if (newPassword !== repeat) {
      setError("Las dos contraseñas no coinciden.");
      return;
    }

    setSending(true);
    const result = await dispatch(changePasswordThunk({ currentPassword, newPassword }));
    setSending(false);

    if (changePasswordThunk.rejected.match(result)) {
      setError(result.payload?.message ?? "No se pudo cambiar la contraseña.");
      return;
    }

    dispatch(showToast("Contraseña cambiada. Vuelve a entrar con la nueva.", "success"));
    dispatch(logout());
    navigate("/login", { replace: true });
  };

  return (
    <form onSubmit={submit} className="w-full">
      <ITFlex direction="column" gap={4}>
        {user?.mustChangePassword && (
          <ITAlert variant="warning" title="Contraseña temporal">
            Tu contraseña es temporal. Cámbiala para poder usar el sistema.
          </ITAlert>
        )}

        <ITInput
          name="currentPassword"
          label="Contraseña actual"
          type="password"
          value={currentPassword}
          onChange={(event) => setCurrentPassword(event.target.value)}
          required
          autoFocus
        />
        <ITInput
          name="newPassword"
          label="Contraseña nueva"
          type="password"
          value={newPassword}
          onChange={(event) => setNewPassword(event.target.value)}
          required
        />
        <ITInput
          name="repeat"
          label="Repite la contraseña nueva"
          type="password"
          value={repeat}
          onChange={(event) => setRepeat(event.target.value)}
          required
        />

        {error && <ITAlert variant="error" title="No se pudo cambiar">{error}</ITAlert>}

        <ITButton
          type="submit"
          label={sending ? "Cambiando…" : "Cambiar contraseña"}
          color="primary"
          disabled={sending}
        />
      </ITFlex>
    </form>
  );
}
