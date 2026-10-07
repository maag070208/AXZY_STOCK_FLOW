import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { HashRouter } from "react-router-dom";
import "@axzydev/axzy_ui_system/dist/index.css";
import "@shared/i18n/config";
import { ThemeProvider } from "@shared/ui/theme";
import App from "./App";
import { AppProviders } from "./providers/AppProviders";
import "@app/index.css";

/**
 * Arranque de la web.
 *
 * Decisiones que conviene no cambiar sin motivo:
 * - **`HashRouter`**: la web se sirve como archivos estáticos detrás de nginx.
 *   Con `BrowserRouter` una recarga en `/invoices/15` pide esa ruta al servidor,
 *   que no existe como archivo y devuelve 404. El proyecto de referencia usa
 *   `HashRouter` por la misma razón.
 * - El tema se monta por fuera del router para que el sidebar y la navbar ya
 *   tengan sus colores en el primer render.
 * - `index.css` se importa **al final**: define las capas de Tailwind y el orden
 *   de importación decide qué hoja gana.
 */
const container = document.getElementById("root");
if (!container) {
  throw new Error("Falta el contenedor #root en index.html");
}

createRoot(container).render(
  <StrictMode>
    <ThemeProvider>
      <HashRouter>
        <AppProviders>
          <App />
        </AppProviders>
      </HashRouter>
    </ThemeProvider>
  </StrictMode>
);
