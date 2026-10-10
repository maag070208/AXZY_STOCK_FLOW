import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./locales/en.json";

/**
 * La interfaz en inglés, con i18next. Todo texto que ve el usuario sale de aquí: los componentes
 * llaman `i18n.t("nav.home")` y nunca escriben el texto literal.
 *
 * Solo hay un idioma por ahora. Añadir otro es agregar su archivo en `locales/` y su bloque en
 * `resources`; el código que llama a `t` no cambia.
 */
void i18n.use(initReactI18next).init({
  lng: "en",
  fallbackLng: "en",
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
});

export default i18n;
