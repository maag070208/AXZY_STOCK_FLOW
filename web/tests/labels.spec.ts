import { expect, test, type Page } from "@playwright/test";

/**
 * La cola de impresión y su reimpresión, **con la API simulada**.
 *
 * Reimprimir gasta una etiqueta más y el contrato exige un motivo: se afirma que la web no envía
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
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByText("Estado del sistema y accesos del catálogo")).toBeVisible();
}

test.describe("etiquetas: reimpresión (API simulada)", () => {
  test("reimprimir sin motivo no envía nada: el botón de confirmar queda apagado", async ({ page }) => {
    let requests = 0;
    await page.route("**/v1/labels/jobs?*", (route) =>
      route.fulfill({ json: { items: [job()], page: 0, size: 20, totalItems: 1, totalPages: 1 } }),
    );
    await page.route("**/v1/labels/jobs/9/reprint", (route) => {
      requests += 1;
      return route.fulfill({ status: 202, json: job({ id: 10, status: "PENDING", attempts: 0, printedAt: null, reprintedFromId: 9 }) });
    });
    await signIn(page);
    await page.getByText("Etiquetas", { exact: true }).first().click();
    await expect(page.getByText("Etiquetas pedidas, impresas y fallidas.")).toBeVisible();

    await page.getByRole("button", { name: "Reimprimir" }).click();
    await expect(page.getByRole("button", { name: "Reimprimir" }).last()).toBeDisabled();
    expect(requests).toBe(0);
  });

  test("reimprimir con motivo envía el motivo y la cola muestra el trabajo nuevo enlazado", async ({ page }) => {
    let queue = [job()];
    await page.route("**/v1/labels/jobs?*", (route) =>
      route.fulfill({ json: { items: queue, page: 0, size: 20, totalItems: queue.length, totalPages: 1 } }),
    );
    await page.route("**/v1/labels/jobs/9/reprint", (route) => {
      queue = [job({ id: 10, status: "PENDING", attempts: 0, printedAt: null, reprintedFromId: 9 }), ...queue];
      return route.fulfill({ status: 202, json: queue[0] });
    });
    await signIn(page);
    await page.getByText("Etiquetas", { exact: true }).first().click();
    await expect(page.getByText("ETQ-STD").first()).toBeVisible();

    await page.getByRole("button", { name: "Reimprimir" }).click();
    await page.locator('input[name="reason"]').fill("  Etiqueta dañada en el estante  ");

    const request = page.waitForRequest((req) => req.url().endsWith("/v1/labels/jobs/9/reprint"));
    await page.getByRole("button", { name: "Reimprimir", exact: true }).last().click();
    // El motivo sale sin los espacios de los extremos: es el texto que quedará en la bitácora.
    expect((await request).postDataJSON()).toEqual({ reason: "Etiqueta dañada en el estante" });

    await expect(page.getByText("Reimpresión pedida. Aparece en la cola como un trabajo nuevo.")).toBeVisible();
    await expect(page.getByText("#9")).toBeVisible();
  });
});
