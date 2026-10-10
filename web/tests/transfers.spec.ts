import { expect, test, type Page } from "@playwright/test";

/**
 * Las acciones de un traslado, **con la API simulada**.
 *
 * Se afirma qué petición hace la web (la acción correcta, sobre el traslado correcto), qué
 * botones ofrece en cada estado y qué muestra cuando la API responde un error. Las reglas de
 * inventario (dos mitades en una transacción, orden de las sumas) las prueban los tests de Kotlin.
 */

const PERMISSIONS = [
  "transfer.view",
  "transit.view",
  "transfer.dispatch",
  "transfer.receive",
  "transfer.cancel",
];

const USER = {
  id: 1,
  username: "jefa",
  name: "Jefa de almacén",
  roles: ["GERENTE_OPERACIONES"],
  permissions: PERMISSIONS,
  scopes: { "transfer.view": "ALL" },
  warehouses: [1],
  mustChangePassword: false,
};

const SESSION = { accessToken: "token-de-prueba", refreshToken: "refresh-de-prueba", expiresIn: 900, user: USER };

const header = (status: string) => ({
  id: 5,
  code: "TR-0005",
  fromWarehouseId: 1,
  fromWarehouseCode: "BOD1",
  toWarehouseId: 2,
  toWarehouseCode: "BOD2",
  status,
  lineCount: 1,
  totalUnits: "4.000",
  note: null,
  dispatchedBy: null,
  dispatchedByName: null,
  dispatchedAt: null,
  receivedBy: null,
  receivedByName: null,
  receivedAt: null,
});

const LINE = {
  id: 9,
  productId: 1,
  productCode: "ABRAA1B",
  productName: "ABRAZADERA DE ARRANQUE",
  quantity: "4.000",
  fromLocationId: 10,
  fromLocationDisplayName: "A-01-02",
  note: null,
};

const detail = (status: string) => ({ transfer: header(status), lines: [LINE] });

async function signIn(page: Page) {
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));

  await page.goto("/");
  await page.locator('input[name="username"]').fill("jefa");
  await page.locator('input[name="password"]').fill("lo-que-sea");
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "Inicio", exact: true })).toBeVisible();
}

/**
 * Lista con el traslado, y el detalle que devuelve `current` (que cambia con cada acción). Las rutas
 * específicas van después de las generales: Playwright da prioridad a la última que se registra.
 */
async function mockTransfer(page: Page, initialStatus: string) {
  let current = detail(initialStatus);
  await page.route("**/v1/transfers?*", (route) =>
    route.fulfill({ json: { items: [header(current.transfer.status)], page: 0, size: 20, totalItems: 1, totalPages: 1 } }),
  );
  await page.route("**/v1/transfers/in-transit*", (route) => route.fulfill({ json: [] }));
  await page.route("**/v1/transfers/5", (route) => route.fulfill({ json: current }));
  return {
    set: (next: ReturnType<typeof detail>) => (current = next),
  };
}

async function openTransfers(page: Page) {
  await page.getByText("Traslados", { exact: true }).first().click();
  await expect(page.getByText("Mercancía entre bodegas y la que va en camino.")).toBeVisible();
  await page.getByText("TR-0005").first().click();
  await expect(page.getByRole("heading", { name: "Traslado TR-0005" })).toBeVisible();
}

test.describe("traslados: acciones (API simulada)", () => {
  test("despachar un borrador llama a /dispatch sin cuerpo y muestra el nuevo estado", async ({ page }) => {
    const state = await mockTransfer(page, "DRAFT");
    await page.route("**/v1/transfers/5/dispatch", (route) => {
      state.set(detail("DISPATCHED"));
      return route.fulfill({ json: detail("DISPATCHED") });
    });
    await signIn(page);
    await openTransfers(page);

    const request = page.waitForRequest((req) => req.method() === "POST" && req.url().endsWith("/v1/transfers/5/dispatch"));
    await page.getByRole("button", { name: "Despachar" }).click();
    expect((await request).postData()).toBeNull();

    await expect(page.getByText("DISPATCHED").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Despachar" })).toHaveCount(0);
  });

  test("recibir un traslado que la API no admite muestra su motivo y no cambia el estado", async ({ page }) => {
    await mockTransfer(page, "DISPATCHED");
    await page.route("**/v1/transfers/5/receive", (route) =>
      route.fulfill({ status: 409, json: { code: "INVALID_TRANSITION", message: "El traslado no está en camino: no se puede recibir." } }),
    );
    await signIn(page);
    await openTransfers(page);

    await page.getByRole("button", { name: "Recibir" }).click();

    await expect(page.getByText("El traslado no está en camino: no se puede recibir.")).toBeVisible();
    await expect(page.getByText("DISPATCHED").first()).toBeVisible();
  });

  test("cancelar un despachado pide confirmación antes de enviar, y dice a dónde vuelve la mercancía", async ({ page }) => {
    const state = await mockTransfer(page, "DISPATCHED");
    let cancelled = 0;
    await page.route("**/v1/transfers/5/cancel", (route) => {
      cancelled += 1;
      state.set(detail("CANCELLED"));
      return route.fulfill({ json: detail("CANCELLED") });
    });
    await signIn(page);
    await openTransfers(page);

    await page.getByRole("button", { name: "Cancelar traslado" }).click();
    await expect(page.getByText("La mercancía vuelve a su bodega de origen (BOD1).")).toBeVisible();
    expect(cancelled, "no debe enviarse antes de confirmar").toBe(0);

    await page.getByRole("button", { name: "Sí, cancelar" }).click();
    await expect(page.getByText("CANCELLED").first()).toBeVisible();
    expect(cancelled).toBe(1);
  });
});
