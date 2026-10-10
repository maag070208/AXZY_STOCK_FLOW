import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./locales/en.json";

/**
 * The interface in English, with i18next. Every text the user sees comes from here: the components
 * call `i18n.t("nav.home")` and never write the literal text.
 *
 * There is only one language for now. Adding another means adding its file in `locales/` and its block in
 * `resources`; the code that calls `t` does not change.
 */
void i18n.use(initReactI18next).init({
  lng: "en",
  fallbackLng: "en",
  resources: { en: { translation: en } },
  interpolation: { escapeValue: false },
});

export default i18n;
