import type { Page } from "@playwright/test";

/**
 * The home page requests its figures on entry (products, approval inbox, transit and labels
 * failed). Without a simulated response, those requests reach the real API with the test token, the
 * API responds 401 and the session closes. This mock leaves the start empty and stable.
 *
 * Must be registered BEFORE each test's mocks (in a `beforeEach`): Playwright gives
 * priority goes to the last registered route, so a test that mocks the same endpoint overwrites it.
 */
export async function mockHomeSummary(page: Page): Promise<void> {
  const empty = { items: [], page: 0, size: 1, totalItems: 0, totalPages: 0 };
  await page.route("**/v1/products?*", (route) => route.fulfill({ json: empty }));
  await page.route("**/v1/invoices/pending-approval*", (route) => route.fulfill({ json: { items: [] } }));
  await page.route("**/v1/transfers/in-transit*", (route) => route.fulfill({ json: [] }));
  await page.route("**/v1/labels/jobs?*", (route) => route.fulfill({ json: empty }));
}
