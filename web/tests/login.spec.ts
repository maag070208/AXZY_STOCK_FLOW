import { expect, test } from "@playwright/test";
import { E2E } from "./support/env";

/**
 * The sign-in, against the **real API**.
 *
 * There is no test that "simulates" the sign-in: what we want to know is whether the web
 * and the API are understood. The credentials come from the `.env`, and the test **fails**
 * if they are missing instead of being skipped: a skipped test counts as passed, and
 * hid gaps before (docs/pruebas/estrategia-pruebas.md).
 */
test.describe("sign-in", () => {
  test("the API responds before starting", async ({ request }) => {
    const health = await request.get(`${E2E.apiUrl}/v1/health`);

    expect(
      health.ok(),
      `La API no responde en ${E2E.apiUrl}. Levántala con: docker compose up -d`,
    ).toBeTruthy();
  });

  test("the brand is served and the manifest carries the product name", async ({ request }) => {
    // The files that `index.html` declares have to exist for real: a
    // a favicon that is not served breaks nothing, and nobody notices.
    for (const asset of ["/favicon.svg", "/favicon.ico", "/apple-touch-icon.png", "/icon-192.png", "/site.webmanifest"]) {
      const response = await request.get(asset);
      expect(response.ok(), `${asset} no se sirve`).toBeTruthy();
    }

    const manifest = await (await request.get("/site.webmanifest")).json();
    expect(manifest.short_name).toBe("StockFlow");
    expect(manifest.icons.length).toBeGreaterThanOrEqual(4);
  });

  test("the sign-in screen renders", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByText("Warehouse management")).toBeVisible();
    await expect(page.locator('input[name="username"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();

    // With the fields empty the button is disabled on purpose: it cannot be
    // sending a request that the API is going to reject for a missing field.
    const entrar = page.getByRole("button", { name: "Sign in" });
    await expect(entrar).toBeDisabled();
    await page.locator('input[name="username"]').fill("alguien");
    await page.locator('input[name="password"]').fill("algo");
    await expect(entrar).toBeEnabled();
  });

  test("wrong credentials show the translated error message", async ({ page }) => {
    await page.goto("/");

    /*
     * A DIFFERENT name in each run. The lockout by attempts is per name
     * of user (I2: five failures in 15 minutes), so with a fixed name the
     * sixth run would get `ACCOUNT_LOCKED` —which is correct— and the test
     * would fail because of its own repetition, not because of a defect.
     */
    await page.locator('input[name="username"]').fill(`no-existe-${Date.now()}`);
    await page.locator('input[name="password"]').fill("contrasena-incorrecta-123");
    await page.getByRole("button", { name: "Sign in" }).click();

    // The text is written by the API. The web does not invent it: if it did, it would lie
    // as soon as the account was locked, which is another case with another remedy.
    await expect(page.getByText("Wrong username or password.")).toBeVisible();
    await expect(page).toHaveURL(/#\/login$/);
  });

  test("the environment credentials sign in, and the API decides the screen", async ({ page }) => {
    expect(
      E2E.adminPassword,
      "Falta ADMIN_BOOTSTRAP_PASSWORD en el .env del repositorio: sin credenciales no se puede probar la entrada.",
    ).not.toBe("");

    await page.goto("/");
    await page.locator('input[name="username"]').fill(E2E.adminUser);
    await page.locator('input[name="password"]').fill(E2E.adminPassword);
    await page.getByRole("button", { name: "Sign in" }).click();

    /*
     * Two outcomes, and the API decides both, not the web:
     *  - temporary password → the change screen, without menu (I8);
     *  - password already changed → the frame, with "Home".
     * The test accepts both on purpose: which one applies depends on the state of the
     * account, and asserting only one would make it fragile without gaining anything.
     */
    await expect(
      page.getByRole("heading", { name: /^(Home|Change password)$/ }).first(),
    ).toBeVisible();
  });
});
