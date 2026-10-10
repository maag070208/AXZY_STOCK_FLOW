import { ITAlert, ITButton, ITFlex, ITInput, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState, type FormEvent } from "react";
import { useDispatch, useSelector } from "react-redux";
import { useNavigate } from "react-router-dom";
import type { AppDispatch, RootState } from "@app/store";
import { loginThunk } from "@entities/session";
import i18n from "@shared/i18n";

/**
 * El formulario de entrada.
 *
 * No valida credenciales: las manda y muestra lo que responde la API. Un
 * «usuario o contraseña incorrectos» calculado en el cliente mentiría en cuanto
 * la cuenta esté bloqueada, que es otra cosa distinta y con otro remedio
 * (`ACCOUNT_LOCKED`, docs/api/errores.md §1.3-bis).
 */
export default function LoginForm() {
  const dispatch = useDispatch<AppDispatch>();
  const navigate = useNavigate();
  const { token, user, status, error } = useSelector((state: RootState) => state.auth);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  // Al entrar: si la contraseña es temporal, la única pantalla posible es cambiarla.
  useEffect(() => {
    if (!token) return;
    navigate(user?.mustChangePassword ? "/change-password" : "/", { replace: true });
  }, [token, user, navigate]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void dispatch(loginThunk({ username, password }));
  };

  return (
    <form onSubmit={submit} className="w-full">
      <ITFlex direction="column" gap={4}>
        <ITInput
          name="username"
          label={i18n.t("auth.username")}
          placeholder={i18n.t("auth.usernamePlaceholder")}
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          required
          autoFocus
        />
        <ITInput
          name="password"
          label={i18n.t("auth.password")}
          type="password"
          placeholder={i18n.t("auth.passwordPlaceholder")}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          required
        />

        {error && <ITAlert variant="error" title={i18n.t("auth.signInFailed")}>{error}</ITAlert>}

        <ITButton
          type="submit"
          label={status === "loading" ? i18n.t("auth.signingIn") : i18n.t("auth.signIn")}
          color="primary"
          disabled={status === "loading" || username === "" || password === ""}
        />

        <ITText muted className="text-xs">
          Si es la primera vez que entras, la aplicación te pedirá cambiar la contraseña
          antes de dejarte ver nada más.
        </ITText>
      </ITFlex>
    </form>
  );
}
