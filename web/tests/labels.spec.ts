import { expect, test, type Page } from "@playwright/test";
import { openMenu } from "./support/menu";
import { mockHomeSummary } from "./support/home";

/**
 * La cola de impresión y su reimpresión, **con la API simulada**.
 *
 * Reprint gasta una etiqueta más y el contrato exige un motivo: se afirma que la web no envía
 * nada sin motivo, que envía el motivo escrito y que la cola muestra el trabajo nuevo que la API crea.
 */

const PERMISSIONS = ["label.queue_view", "label.reprint"];

const USER = {
  id: 1,
  username: "etiquetas",
  name: "Responsable de etiquetas",
  roles: ["OPERADOR"],
  permissions: PERMISSIONS,
  scopes: {},
  warehouses: [1],
  mustChangePassword: false,
};

const SESSION = { accessToken: "token-de-prueba", refreshToken: "refresh-de-prueba", expiresIn: 900, user: USER };

const job = (overrides: Record<string, unknown> = {}) => ({
  id: 9,
  templateCode: "ETQ-STD",
  copies: 2,
  status: "PRINTED",
  attempts: 1,
  createdAt: "2026-10-09T15:00:00Z",
  printedAt: "2026-10-09T15:01:00Z",
  lastError: null,
  reprintedFromId: null,
  ...overrides,
});

async function signIn(page: Page) {
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));

  await page.goto("/");
  await page.locator('input[name="username"]').fill("etiquetas");
  await page.locator('input[name="password"]').fill("lo-que-sea");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Home", exact: true })).toBeVisible();
}

test.beforeEach(async ({ page }) => mockHomeSummary(page));

test.describe("labels: reprint (simulated API)", () => {
  test("reprinting without a reason sends nothing: the confirm button stays disabled", async ({ page }) => {
    let requests = 0;
    await page.route("**/v1/labels/jobs?*", (route) =>
      route.fulfill({ json: { items: [job()], page: 0, size: 20, totalItems: 1, totalPages: 1 } }),
    );
    await page.route("**/v1/labels/jobs/9/reprint", (route) => {
      requests += 1;
      return route.fulfill({ status: 202, json: job({ id: 10, status: "PENDING", attempts: 0, printedAt: null, reprintedFromId: 9 }) });
    });
    await signIn(page);
    await openMenu(page, "Labels");
    await expect(page.getByText("Labels requested, printed and failed.")).toBeVisible();

    await page.getByRole("button", { name: "Reprint" }).click();
    await expect(page.getByRole("button", { name: "Reprint" }).last()).toBeDisabled();
    expect(requests).toBe(0);
  });

  test("reprinting with a reason sends the reason and the queue shows the linked new job", async ({ page }) => {
    let queue = [job()];
    await page.route("**/v1/labels/jobs?*", (route) =>
      route.fulfill({ json: { items: queue, page: 0, size: 20, totalItems: queue.length, totalPages: 1 } }),
    );
    await page.route("**/v1/labels/jobs/9/reprint", (route) => {
      queue = [job({ id: 10, status: "PENDING", attempts: 0, printedAt: null, reprintedFromId: 9 }), ...queue];
      return route.fulfill({ status: 202, json: queue[0] });
    });
    await signIn(page);
    await openMenu(page, "Labels");
    await expect(page.getByText("ETQ-STD").first()).toBeVisible();

    await page.getByRole("button", { name: "Reprint" }).click();
    await page.locator('input[name="reason"]').fill("  Etiqueta dañada en el estante  ");

    const request = page.waitForRequest((req) => req.url().endsWith("/v1/labels/jobs/9/reprint"));
    await page.getByRole("button", { name: "Reprint", exact: true }).last().click();
    // El motivo sale sin los espacios de los extremos: es el texto que quedará en la bitácora.
    expect((await request).postDataJSON()).toEqual({ reason: "Etiqueta dañada en el estante" });

    await expect(page.getByText("Reprint requested. It appears in the queue as a new job.")).toBeVisible();
    await expect(page.getByText("#9")).toBeVisible();
  });
});
