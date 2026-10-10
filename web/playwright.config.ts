import { defineConfig, devices } from "@playwright/test";
import { E2E } from "./tests/support/env";

/**
 * Las pruebas de navegador corren contra la aplicación de verdad (Vite) y, en el
 * caso de la entrada, contra la **API de verdad**.
 *
 * Serie y un solo trabajador: comparten la API y la base, y dos corridas a la vez
 * se pisan (docs/pruebas/verificacion.md §6).
 */
export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,

  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },

  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],

  use: {
    baseURL: E2E.webUrl,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    // Sin vídeo: donde no hay el ffmpeg de Playwright, grabarlo rompe la corrida.
    video: "off",
    locale: "es-MX",
    timezoneId: "America/Mexico_City",
  },

  projects: [
    {
      name: "chromium",
      // 1280 px: es el ancho en el que se verifica que el menú lateral se ve
      // (docs/arquitectura/web-react.md §5).
      use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } },
    },
  ],

  // Si ya está corriendo, se reutiliza: levantar otro servidor en el mismo puerto
  // falla y esconde el problema real.
  webServer: {
    command: "pnpm dev",
    url: E2E.webUrl,
    reuseExistingServer: true,
    timeout: 120_000,
    stdout: "ignore",
    stderr: "pipe",
  },
});
