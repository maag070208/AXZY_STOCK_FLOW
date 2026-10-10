import type { Page } from "@playwright/test";

/**
 * El inicio pide sus cifras al entrar (productos, bandeja de aprobación, tránsito y etiquetas
 * fallidas). Sin respuesta simulada, esas peticiones llegan al API real con el token de prueba, el
 * API responde 401 y la sesión se cierra. Este mock deja el inicio vacío y estable.
 *
 * Debe registrarse ANTES de las simulaciones de cada prueba (en un `beforeEach`): Playwright da
 * prioridad a la última ruta registrada, así que una prueba que mockea el mismo endpoint lo sobrescribe.
 */
export async function mockHomeSummary(page: Page): Promise<void> {
  const empty = { items: [], page: 0, size: 1, totalItems: 0, totalPages: 0 };
  await page.route("**/v1/products?*", (route) => route.fulfill({ json: empty }));
  await page.route("**/v1/invoices/pending-approval*", (route) => route.fulfill({ json: { items: [] } }));
  await page.route("**/v1/transfers/in-transit*", (route) => route.fulfill({ json: [] }));
  await page.route("**/v1/labels/jobs?*", (route) => route.fulfill({ json: empty }));
}
