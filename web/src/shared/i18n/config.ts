import i18n from "i18next";
import { initReactI18next } from "react-i18next";

import commonEn from "./locales/en/common.json";
import authEn from "./locales/en/auth.json";
import commonEs from "./locales/es/common.json";
import authEs from "./locales/es/auth.json";

/**
 * i18n desde el primer día, en español e inglés.
 *
 * Se monta ahora —y no al final— porque el contrato obliga a decidir con `code`
 * y traducir el mensaje: si los textos vivieran sueltos en los componentes, la
 * decisión por `code` (§1.3) se acabaría mezclando con comparaciones de texto.
 *
 * Los namespaces crecen por pantalla (`inventory`, `invoices`, …) igual que en
 * el proyecto de referencia.
 */
export const defaultNS = "common" as const;

export const APP_LANGUAGES = ["es", "en"] as const;
export type AppLanguage = (typeof APP_LANGUAGES)[number];

export const NS_LIST = ["common", "auth"] as const;

export const resources = {
  es: { common: commonEs, auth: authEs },
  en: { common: commonEn, auth: authEn },
} as const;

const STORAGE_KEY = "almacen-integral-language";

const storedLanguage =
  typeof window !== "undefined" ? window.localStorage.getItem(STORAGE_KEY) : null;

void i18n.use(initReactI18next).init({
  resources,
  // Español por defecto: el almacén opera en español y el legacy tenía el
  // inglés a medias (60 cadenas sueltas y validaciones sin traducir).
  lng: storedLanguage ?? "es",
  fallbackLng: "es",
  defaultNS,
  ns: [...NS_LIST],
  interpolation: { escapeValue: false },
  returnNull: false,
});

i18n.on("languageChanged", (language) => {
  if (typeof window !== "undefined") {
    window.localStorage.setItem(STORAGE_KEY, language);
  }
});

export default i18n;
