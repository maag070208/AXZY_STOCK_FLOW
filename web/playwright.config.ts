import { defineConfig, devices } from "@playwright/test";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONFIGURACIÓN DEL E2E DE NAVEGADOR
 *
 * **NO ARRANCA NADA POR SU CUENTA, y es deliberado.**
 *
 * El e2e de navegador necesita tres piezas coordinadas: una base con esquema y
 * semilla, un backend sirviendo esa base y una web apuntando a ese backend. Si
 * Playwright arrancara los servidores con su `webServer`, el puerto y la base
 * quedarían decididos en dos sitios —este archivo y el script— y **el fallo más
 * caro posible es que el test pase contra el backend equivocado**. El entorno lo
 * levanta `scripts/e2e-web.sh`, que es un único sitio donde mirar, y aquí solo se
 * consume.
 *
 * Los dos orígenes llegan por variable de entorno. Los valores por defecto son
 * los del script, para que `pnpm test:e2e` funcione si alguien ya los tiene
 * levantados a mano.
 * ═══════════════════════════════════════════════════════════════════════════
 */

const BASE_URL = process.env.E2E_BASE_URL ?? "http://127.0.0.1:5174";

export default defineConfig({
  testDir: "./e2e",

  /**
   * Un solo worker. **Los specs comparten una base de datos**, y el sembrado de
   * identidad más el orden de los listados hacen que ejecutarlos en paralelo
   * produzca fallos que dependen del orden — la peor clase de test, porque pasa
   * en la máquina de quien lo escribió y falla en la de al lado.
   */
  workers: 1,
  fullyParallel: false,

  /** Un `test.only` olvidado en CI no debe silenciar el resto de la suite. */
  forbidOnly: !!process.env.CI,

  /** Reintentos: 0. Un test intermitente es un test roto, y hay que verlo. */
  retries: 0,

  reporter: [["list"]],

  use: {
    baseURL: BASE_URL,

    /**
     * Traza y captura SOLO cuando algo falla. En verde no se genera nada: un
     * artefacto por test llenaría el disco sin que nadie lo mire.
     */
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",

    locale: "es-ES",
    timezoneId: "America/Mexico_City",
  },

  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
});
