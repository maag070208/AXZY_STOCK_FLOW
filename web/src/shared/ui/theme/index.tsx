import { ITThemeProvider, type ITThemePalette } from "@axzydev/axzy_ui_system";
import type { ReactNode } from "react";

/**
 * Theme de la web.
 *
 * Se reutiliza `ITThemeProvider` del UI kit —el mismo que usa el proyecto de
 * referencia— en lugar de escribir estilos propios: cada componente del kit lee
 * sus colores de estas variables, así que cambiar la paleta aquí cambia toda la
 * aplicación y no hay clases sueltas que mantener.
 *
 * La paleta es deliberadamente distinta a la del proyecto de referencia (que es
 * azul hotelero): aquí el acento es verde industrial, para que el personal no
 * confunda las dos aplicaciones cuando las tiene abiertas a la vez.
 */
const ALMACEN_THEME: Partial<ITThemePalette> = {
  primary: "#166534",
  secondary: "#15803D",
  ternary: "#F1F5F4",
  alert: "#B45309",
  warning: "#D97706",
  danger: "#B91C1C",
  info: "#1D4ED8",
  success: "#15803D",
  layout: {
    sidebarBg: "#ffffff",
    sidebarText: "#334155",
    navbarBg: "#166534",
    navbarText: "#ffffff",
  },
  table: {
    headerBg: "#dcfce7",
    headerText: "#14532d",
    rowBg: "#ffffff",
    rowText: "#0f172a",
    rowHover: "#ecfdf5",
  },
};

export const appTheme: Partial<ITThemePalette> = ALMACEN_THEME;

export const ThemeProvider = ({ children }: { children: ReactNode }): ReactNode => (
  // `showFab={false}`: el diseñador de tema del kit es una herramienta de
  // desarrollo; en piso sólo agrega un botón flotante que nadie debe pulsar.
  <ITThemeProvider theme={appTheme} showFab={false} density={1} radius={6}>
    {children}
  </ITThemeProvider>
);
