import { ITThemeProvider, type ITThemePalette } from "@axzydev/axzy_ui_system";
import { store } from "@app/store";
import React from "react";
import { createRoot } from "react-dom/client";
import { Provider } from "react-redux";
import { HashRouter } from "react-router-dom";
import App from "./App";
import ToastProvider from "./toast/ToastProvider";
import "@shared/i18n";
import "@app/index.css";

/*
 * El modo oscuro no se usa, y no se deja al sistema operativo: si no hay
 * preferencia guardada se fija `light`. Sin esto, una máquina en oscuro abre la
 * aplicación con un tema que nadie diseñó (docs/arquitectura/web-react.md §2).
 */
if (!localStorage.getItem("it-theme-dark-mode")) {
  localStorage.setItem("it-theme-dark-mode", "light");
}

/**
 * Los colores de StockFlow, **definidos una sola vez** (docs/guia/identidad-visual.md):
 * azul marino de marca, turquesa de apoyo y naranja para lo que reclama atención.
 * Lenguaje plano: superficies blancas, borde fino y sin sombras.
 */
const stockTheme: ITThemePalette = {
  primary: "#16315F",
  secondary: "#3A86A8",
  ternary: "#EEF2FB",

  alert: "#F28C28",
  warning: "#F59E0B",
  danger: "#DC2626",
  info: "#3A86A8",
  success: "#16A34A",

  layout: {
    sidebarBg: "#F2F6FC",
    sidebarText: "#334155",
    navbarBg: "#FFFFFF",
    navbarText: "#1E293B",
  },

  table: {
    headerBg: "#F2F6FC",
    headerText: "#475569",
    rowBg: "#FFFFFF",
    rowText: "#1E293B",
    rowHover: "#F5F7FB",
  },
};

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Provider store={store}>
      <ITThemeProvider theme={stockTheme} showFab={false} density={1} appearance="flat" radius={10} shadow={1}>
        <ToastProvider>
          <HashRouter>
            <App />
          </HashRouter>
        </ToastProvider>
      </ITThemeProvider>
    </Provider>
  </React.StrictMode>,
);
