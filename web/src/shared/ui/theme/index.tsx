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
 * La paleta es la OFICIAL de StockFlow, la misma que usan el móvil, Android e iOS.
 * Está en un solo sitio: cambiar un color aquí cambia toda la aplicación, porque
 * cada componente del kit lee sus colores de estas variables.
 */
/**
 * La paleta OFICIAL de StockFlow.
 *
 * **Antes esto era verde industrial y estaba mal.** El agente que construyó el
 * scaffold no tenía los assets y eligió una paleta «para no confundirse con el
 * proyecto de referencia». Ahora existe el manual de marca, así que la web, el
 * móvil, Android e iOS usan LOS MISMOS colores:
 *
 *   azul marino  #16315F   primario: cabecera, sidebar, acciones principales
 *   naranja      #F28C28   acento: lo que reclama atención
 *   turquesa     #3A86A8   secundario: enlaces, estados informativos
 *   fondo claro  #F2F6FC
 *   fondo oscuro #0C1730
 *
 * El `favicon.svg` del kit ya cambia solo con el modo del sistema, así que el
 * icono y la interfaz no se pueden desincronizar.
 */
const ALMACEN_THEME: Partial<ITThemePalette> = {
  primary: "#16315F",
  secondary: "#3A86A8",
  ternary: "#F2F6FC",
  // El naranja es el ACENTO y no el primario a propósito: un acento que se usa
  // para todo deja de llamar la atención, y aquí tiene que señalizar lo urgente
  // (una discrepancia sin aprobar, una etiqueta que falló al imprimir).
  alert: "#F28C28",
  warning: "#E8A33D",
  danger: "#C0392B",
  info: "#3A86A8",
  success: "#2E7D5B",
  layout: {
    sidebarBg: "#FFFFFF",
    sidebarText: "#16315F",
    navbarBg: "#16315F",
    navbarText: "#FFFFFF",
  },
  table: {
    // El azul muy claro del fondo de la cabecera sale de aclarar el primario, no
    // de un azul distinto: así la tabla parece parte de la misma familia.
    headerBg: "#E4EBF5",
    headerText: "#16315F",
    rowBg: "#FFFFFF",
    rowText: "#0F1E33",
    rowHover: "#F2F6FC",
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
