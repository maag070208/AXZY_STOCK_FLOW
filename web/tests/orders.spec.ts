import { expect, test, type Page } from "@playwright/test";

/**
 * Las acciones del surtido, **con la API simulada**.
 *
 * Se afirma lo que la web envía (la línea correcta, la cantidad, el motivo), lo que no envía cuando
 * falta un dato, y lo que muestra cuando el ERP no responde. Que el empaque no pase del pendiente o
 * que un faltante sea un ajuste lo prueban los tests de Kotlin con la base real.
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
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "Inicio", exact: true })).toBeVisible();
}

/** Lista con la orden, y la orden que devuelve `current` (que cambia con cada acción). */
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
  await page.getByText("Surtido", { exact: true }).first().click();
  await expect(page.getByText("Órdenes, su empaque y su exportación al ERP.")).toBeVisible();
  await page.getByText("ORD-2024-0042").first().click();
  await expect(page.getByRole("heading", { name: "Orden ORD-2024-0042" })).toBeVisible();
}

test.describe("surtido: acciones (API simulada)", () => {
  test("empacar envía la cantidad de esta vez y muestra la orden que devuelve el servidor", async ({ page }) => {
    const state = await mockOrder(page, order());
    await page.route("**/v1/orders/30/lines/88/package", (route) => {
      const packed = order({ packagedLines: 0 }, [{ ...LINE, suppliedQuantity: "7.000" }]);
      state.set(packed);
      return route.fulfill({ json: packed });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Empacar" }).click();
    await page.locator('input[name="quantity"]').fill("7");

    const request = page.waitForRequest((req) => req.url().endsWith("/v1/orders/30/lines/88/package"));
    await page.getByRole("button", { name: "Registrar empaque" }).click();
    expect((await request).postDataJSON()).toEqual({ quantity: 7 });

    await expect(page.getByText("Empaque registrado.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Empacar" })).toBeVisible();
  });

  test("un faltante sin motivo no se envía: la web lo pide antes", async ({ page }) => {
    await mockOrder(page, order());
    let sent = 0;
    await page.route("**/v1/orders/30/shortage", (route) => {
      sent += 1;
      return route.fulfill({ status: 422, json: { code: "REASON_REQUIRED", message: "El faltante necesita un motivo." } });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Faltante" }).click();
    await page.locator('input[name="quantity"]').fill("3");
    await page.getByRole("button", { name: "Registrar faltante" }).click();

    await expect(page.getByText("Elige el motivo del faltante.")).toBeVisible();
    expect(sent, "sin motivo no debe salir ninguna petición").toBe(0);
  });

  test("un faltante con motivo envía la línea, la cantidad y el motivo", async ({ page }) => {
    const state = await mockOrder(page, order());
    await page.route("**/v1/orders/30/shortage", (route) => {
      state.set(order({}, [{ ...LINE, suppliedQuantity: "7.000" }]));
      return route.fulfill({ json: order({}, [{ ...LINE, suppliedQuantity: "7.000" }]) });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Faltante" }).click();
    await page.locator('input[name="quantity"]').fill("3");
    await page.locator('select[name="reason"]').selectOption("SHORTAGE");

    const request = page.waitForRequest((req) => req.url().endsWith("/v1/orders/30/shortage"));
    await page.getByRole("button", { name: "Registrar faltante" }).click();
    expect((await request).postDataJSON()).toEqual({ lineId: 88, quantity: 3, reason: "SHORTAGE" });
  });

  test("exportar con el ERP caído muestra el motivo del fallo, no una exportación hecha", async ({ page }) => {
    const confirmed = order({ status: "CONFIRMED", converted: true, convertedAt: "2026-10-09T15:00:00Z" }, [{ ...LINE, supplied: true, suppliedQuantity: "10.000", status: "LOCATED" }]);
    const state = await mockOrder(page, confirmed);
    const failure = "ERP_NOT_CONNECTED: no hay adaptador del ERP configurado";
    await page.route("**/v1/orders/30/export", (route) => {
      // El intento no devuelve la orden: la web la vuelve a pedir, y entonces ya trae el fallo guardado.
      state.set({ ...confirmed, exportStatus: "FAILED", exportAttempts: 1, exportLastError: failure });
      return route.fulfill({ json: { attempts: 1, status: "FAILED", lastError: failure } });
    });
    await signIn(page);
    await openOrder(page);

    await page.getByRole("button", { name: "Exportar al ERP" }).click();

    await expect(page.getByText("La última exportación falló")).toBeVisible();
    await expect(page.getByText(failure)).toBeVisible();
    await expect(page.getByRole("button", { name: "Reintentar exportación" })).toBeVisible();
  });
});
