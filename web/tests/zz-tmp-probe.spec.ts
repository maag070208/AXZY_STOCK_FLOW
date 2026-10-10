import { expect, test } from "@playwright/test";

test("probe sidebar label width over time after sign-in", async ({ page }) => {
  const USER = { id: 1, username: "jefa", name: "J", roles: ["ADMIN"], permissions: ["product.view", "warehouse.view"], scopes: {}, warehouses: [1], mustChangePassword: false };
  const SESSION = { accessToken: "t", refreshToken: "r", expiresIn: 900, user: USER };
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));
  await page.goto("/");
  await page.locator('input[name="username"]').fill("jefa");
  await page.locator('input[name="password"]').fill("x");
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "Inicio", exact: true })).toBeVisible();
  const samples: number[] = [];
  for (let i = 0; i < 15; i++) {
    const box = await page.getByRole("navigation").getByText("Productos", { exact: true }).boundingBox().catch(() => null);
    samples.push(Math.round(box?.width ?? -1));
    await page.waitForTimeout(200);
  }
  console.log("PROBE widths:", JSON.stringify(samples));
  expect(true).toBe(true);
});
