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
 * Dark mode is not used, and it is not left to the operating system: if there is no
 * saved preference is set to `light`. Without this, a machine in dark mode opens the
 * application with a theme nobody designed (docs/arquitectura/web-react.md §2).
 */
if (!localStorage.getItem("it-theme-dark-mode")) {
  localStorage.setItem("it-theme-dark-mode", "light");
}

/**
 * The StockFlow colors, **defined only once** (docs/guia/identidad-visual.md):
 * brand navy blue, support turquoise and orange for what needs attention.
 * Flat language: white surfaces, thin border and no shadows.
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
