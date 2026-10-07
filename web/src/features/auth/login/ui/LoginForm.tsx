import { ITAlert, ITButton, ITCard, ITFlex, ITText, ITInput } from "@axzydev/axzy_ui_system";
import { useMemo, useState, type FormEvent } from "react";
import { FaRightToBracket } from "react-icons/fa6";
import { useTranslation } from "react-i18next";
import { useLogin } from "../model/useLogin";

/**
 * Formulario de acceso.
 *
 * Sin Formik ni yup: el formulario tiene dos campos y una regla («los dos son
 * obligatorios»). Añadir una librería de formularios aquí sería la misma
 * duplicación de dependencias que el legacy arrastraba (Chakra **y** MUI
 * conviviendo). Cuando existan formularios con validación por campo, se decidirá
 * entonces y en un solo sitio.
 */
export const LoginForm = (): React.ReactNode => {
  const { t } = useTranslation(["auth", "common"]);
  const { submitting, errorCode, errorDetails, submit, reset } = useLogin();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [touchedSubmit, setTouchedSubmit] = useState(false);

  const missingFields = username.trim() === "" || password === "";

  /** `code` del servidor → texto. El cliente jamás compara mensajes (§1.3). */
  const errorMessage = useMemo(() => {
    if (errorCode) {
      const key = `errors.${errorCode}`;
      const translated = t(key, { defaultValue: "" });
      if (translated) {
        return t(key, {
          until: typeof errorDetails?.until === "string" ? errorDetails.until : "",
        });
      }
      return t("errors.UNKNOWN");
    }
    if (touchedSubmit && missingFields) return t("required");
    return null;
  }, [errorCode, errorDetails, missingFields, t, touchedSubmit]);

  const onSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    setTouchedSubmit(true);
    if (missingFields) return;
    void submit(username.trim(), password);
  };

  return (
    <ITCard title={t("title")} className="w-full">
      <form onSubmit={onSubmit} noValidate>
        <ITFlex direction="column" gap={4}>
          <ITText as="p" muted className="text-[13px]">
            {t("subtitle")}
          </ITText>

          <ITInput
            name="username"
            type="text"
            label={t("username")}
            value={username}
            onChange={(event: { target: { value: string } }) => {
              setUsername(event.target.value);
              reset();
            }}
          />

          <ITInput
            name="password"
            type="password"
            label={t("password")}
            value={password}
            onChange={(event: { target: { value: string } }) => {
              setPassword(event.target.value);
              reset();
            }}
          />

          {errorMessage && <ITAlert variant="error">{errorMessage}</ITAlert>}

          <ITButton
            type="submit"
            variant="filled"
            color="primary"
            icon={<FaRightToBracket size={14} />}
            disabled={submitting}
            label={submitting ? t("submitting") : t("submit")}
          />
        </ITFlex>
      </form>
    </ITCard>
  );
};
