/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL base de la API. Sólo se usa si no hay `window.__APP_CONFIG__.API_URL`. */
  readonly VITE_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/** Configuración inyectada en runtime por `public/config.js` (nginx la sobreescribe). */
interface AppRuntimeConfig {
  API_URL?: string;
}

interface Window {
  __APP_CONFIG__?: AppRuntimeConfig;
}

declare module "*.css" {
  const content: string;
  export default content;
}

declare module "*.svg" {
  const src: string;
  export default src;
}

/** Versión de la web, inyectada en build por `vite.config.ts`. */
declare const __APP_VERSION__: string;
