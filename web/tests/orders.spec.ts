import { expect, test, type Page } from "@playwright/test";
import { openMenu } from "./support/menu";
import { mockHomeSummary } from "./support/home";

/**
 * The fulfillment actions, **with the simulated API**.
 *
 * It asserts what the web sends (the correct line, the quantity, the reason), what it does not send when
 * a value is missing, and what it shows when the ERP does not respond. That the packing does not go past the pending amount or
 * that a shortage is an adjustment is tested by the Kotlin tests against the real database.
 */

const PERMISSIONS = [
  "order.view",
  "order.package",
  "order.assign",
  "order.confirm",
  "order.convert",
  "inventory.adjust_shortage",
];

const USER = {
  id: 1,
  username: "operador",
  name: "Operador de piso",
  roles: ["OPERADOR"],
  permissions: PERMISSIONS,
  scopes: { "order.view": "ALL" },
  warehouses: [1],
  mustChangePassword: false,
};

const SESSION = { accessToken: "token-de-prueba", refreshToken: "refresh-de-prueba", expiresIn: 900, user: USER };

const LINE = {
  id: 88,
  productId: 1,
  productCode: "ABRAA1B",
  productName: "ABRAZADERA DE ARRANQUE",
  unitMeasure: "PZA",
  quantity: "10.000",
  suppliedQuantity: "0.000",
  supplied: false,
  status: "ASSIGNED",
  assignedUserId: 1,
  assignedUserName: "Operador de piso",
  locationId: 2,
  locationDisplayName: "04-B-C",
};

const order = (overrides: Record<string, unknown> = {}, lines = [LINE]) => ({
  id: 30,
  orderNumber: "ORD-2024-0042",
  packagingDate: "2026-10-07",
  status: "IN_PROGRESS",
  customerCode: null,
  concept: null,
  converted: false,
  convertedAt: null,
  exportStatus: null,
  exportAttempts: 0,
  exportLastError: null,
  totalLines: lines.length,
  packagedLines: lines.filter((line) => line.supplied).length,
  lines,
  ...overrides,
});

async function signIn(page: Page) {
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));

  await page.goto("/");
  await page.locator('input[name="username"]').fill("operador");
  await page.locator('input[name="password"]').fill("lo-que-sea");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Home", exact: true })).toBeVisible();
}

/** List with the order, and the order returned by `current` (which changes with each action). */
async function mockOrder(page: Page, initial: Record<string, unknown>) {
  let current = initial;
  await page.route("**/v1/orders?*", (route) =>
    route.fulfill({
      json: { items: [{ ...current }], page: 0, size: 20, totalItems: 1, totalPages: 1 },
    }),
  );
  await page.route("**/v1/orders/30", (route) => route.fulfill({ json: current }));
  return { set: (next: Record<string, unknown>) => (current = next) };
}

async function openOrder(page: Page) {
  await openMenu(page, "Fulfillment");
  await expect(page.getByText("Orders, their packing and their export to the ERP.")).toBeVisible();
  await page.getByText("ORD-2024-0042").first().click();
  await expect(page.getByRole("heading", { name: "Order ORD-2024-0042" })).toBeVisible();
}

test.beforeEach(async ({ page }) => mockHomeSummary(page));

test.describe("fulfillment: actions (simulated API)", () => {
  test("packing sends this time's quantity and shows the order the server returns", async ({ page }) => {
    const state = await mockOrder(page, order());
    await page.route("**/v1/orders/30/lines/88/package", (route) => {
      const packed = order({ packagedLines: 0 }, [{ ...LINE, suppliedQuantity: "7.000" }]);
      state.set(packed);
      return route.fulfill({ json: packed });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Pack" }).click();
    await page.locator('input[name="quantity"]').fill("7");

    const request = page.waitForRequest((req) => req.url().endsWith("/v1/orders/30/lines/88/package"));
    await page.getByRole("button", { name: "Record packing" }).click();
    expect((await request).postDataJSON()).toEqual({ quantity: 7 });

    await expect(page.getByText("Packing recorded.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Pack" })).toBeVisible();
  });

  test("a shortage without a reason is not sent: the web asks for it first", async ({ page }) => {
    await mockOrder(page, order());
    let sent = 0;
    await page.route("**/v1/orders/30/shortage", (route) => {
      sent += 1;
      return route.fulfill({ status: 422, json: { code: "REASON_REQUIRED", message: "El faltante necesita un motivo." } });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Shortage" }).click();
    await page.locator('input[name="quantity"]').fill("3");
    await page.getByRole("button", { name: "Record shortage" }).click();

    await expect(page.getByText("Choose the reason for the shortage.")).toBeVisible();
    expect(sent, "sin motivo no debe salir ninguna petición").toBe(0);
  });

  test("a shortage with a reason sends the line, the quantity and the reason", async ({ page }) => {
    const state = await mockOrder(page, order());
    await page.route("**/v1/orders/30/shortage", (route) => {
      state.set(order({}, [{ ...LINE, suppliedQuantity: "7.000" }]));
      return route.fulfill({ json: order({}, [{ ...LINE, suppliedQuantity: "7.000" }]) });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Shortage" }).click();
    await page.locator('input[name="quantity"]').fill("3");
    await page.locator('select[name="reason"]').selectOption("SHORTAGE");

    const request = page.waitForRequest((req) => req.url().endsWith("/v1/orders/30/shortage"));
    await page.getByRole("button", { name: "Record shortage" }).click();
    expect((await request).postDataJSON()).toEqual({ lineId: 88, quantity: 3, reason: "SHORTAGE" });
  });

  test("exporting with the ERP down shows the failure reason, not a fake export", async ({ page }) => {
    const confirmed = order({ status: "CONFIRMED", converted: true, convertedAt: "2026-10-09T15:00:00Z" }, [{ ...LINE, supplied: true, suppliedQuantity: "10.000", status: "LOCATED" }]);
    const state = await mockOrder(page, confirmed);
    const failure = "ERP_NOT_CONNECTED: no hay adaptador del ERP configurado";
    await page.route("**/v1/orders/30/export", (route) => {
      // The attempt does not return the order: the web requests it again, and then it already carries the saved failure.
      state.set({ ...confirmed, exportStatus: "FAILED", exportAttempts: 1, exportLastError: failure });
      return route.fulfill({ json: { attempts: 1, status: "FAILED", lastError: failure } });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Export to ERP" }).click();

    await expect(page.getByText("The last export failed")).toBeVisible();
    await expect(page.getByText(failure)).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry export" })).toBeVisible();
  });
});
