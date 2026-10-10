import { expect, test, type Page } from "@playwright/test";

/**
 * Las acciones de escritura de recepción, **con la API simulada**.
 *
 * Lo que se afirma es lo que la web **envía** y lo que **muestra** ante cada respuesta. Las reglas
 * (segregación de funciones, empaque, bloqueos) las prueban los tests de Kotlin con la base real;
 * aquí se comprueba que la web las presenta sin inventarlas.
 */

const PERMISSIONS = [
  "invoice.view",
  "invoice.create",
  "invoice.capture",
  "invoice.approve",
  "invoice.reject",
  "invoice.close",
  "invoice.assign",
];

const USER = {
  id: 1,
  username: "capturista",
  name: "Capturista",
  roles: ["CAPTURISTA"],
  permissions: PERMISSIONS,
  scopes: { "invoice.view": "ALL" },
  warehouses: [1],
  mustChangePassword: false,
};

const SESSION = { accessToken: "token-de-prueba", refreshToken: "refresh-de-prueba", expiresIn: 900, user: USER };

const page1 = <T,>(items: T[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });

const LINE_PENDING = {
  id: 88,
  productId: 1,
  productCode: "ABRAA1B",
  productName: "ABRAZADERA DE ARRANQUE",
  container: null,
  quantity: "100.000",
  boxQuantity: "2.000",
  unitsPerBox: "50.000",
  physicalQuantity: null,
  physicalBoxQuantity: null,
  locatedQuantity: "0.000",
  difference: null,
  status: "PENDING",
  capturedByName: null,
  capturedAt: null,
  approvedByName: null,
  adjustmentReason: null,
  locationDisplayName: null,
};

const invoice = (lines: unknown[], status = "PENDING") => ({
  id: 15,
  invoiceNumber: "F-2024-0158",
  receiptDate: "2026-10-07",
  invoiceType: "NATIONAL",
  status,
  customerCode: null,
  concept: null,
  totalLines: lines.length,
  pendingApprovalLines: 0,
  createdAt: "2026-10-07T21:24:37.688Z",
  lines,
});

async function signIn(page: Page) {
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));

  await page.goto("/");
  await page.locator('input[name="username"]').fill("capturista");
  await page.locator('input[name="password"]').fill("lo-que-sea");
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByRole("heading", { name: "Inicio", exact: true })).toBeVisible();
}

/** Entra a Recepción desde el menú: un clic dentro de la app, sin recargar (la sesión vive en memoria). */
async function openReceiving(page: Page) {
  await page.getByText("Recepción", { exact: true }).first().click();
  await expect(page.getByText("Facturas recibidas y las que esperan la aprobación de un supervisor.")).toBeVisible();
}

/** La lista de facturas y la bandeja vacías; una factura que aparece en la lista. */
async function mockList(page: Page, lines: unknown[] = []) {
  await page.route("**/v1/invoices/pending-approval*", (route) => route.fulfill({ json: { items: [] } }));
  await page.route("**/v1/invoices?*", (route) =>
    route.fulfill({
      json: page1([
        { id: 15, invoiceNumber: "F-2024-0158", receiptDate: "2026-10-07", invoiceType: "NATIONAL", status: "PENDING", customerCode: null, concept: null, totalLines: lines.length, pendingApprovalLines: 0, createdAt: "2026-10-07T21:24:37.688Z" },
      ]),
    }),
  );
}

test.describe("recepción: escrituras (API simulada)", () => {
  test("registrar una factura envía sus líneas y abre la factura creada", async ({ page }) => {
    await mockList(page);
    await page.route("**/v1/invoices", (route) => {
      if (route.request().method() !== "POST") return route.fallback();
      return route.fulfill({ status: 201, json: { id: 15, invoiceNumber: "F-2024-0158" } });
    });
    await page.route("**/v1/invoices/15", (route) => route.fulfill({ json: invoice([LINE_PENDING]) }));
    await signIn(page);
    await openReceiving(page);

    await page.getByRole("button", { name: "Nueva factura" }).click();
    await page.locator('input[name="invoiceNumber"]').fill("F-2024-0158");
    await page.locator('input[name="productCode-0"]').fill("abraa1b");
    await page.locator('input[name="quantity-0"]').fill("100");
    await page.locator('input[name="boxQuantity-0"]').fill("2");
    await page.locator('input[name="unitsPerBox-0"]').fill("50");

    const request = page.waitForRequest((req) => req.method() === "POST" && new URL(req.url()).pathname === "/v1/invoices");
    await page.getByRole("button", { name: "Registrar factura" }).click();
    const body = (await request).postDataJSON() as Record<string, unknown>;

    expect(body.invoiceNumber).toBe("F-2024-0158");
    expect(body.invoiceType).toBe("NATIONAL");
    expect(body.receiptDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    // El código sale en mayúsculas y las cantidades como número, no como texto.
    expect(body.lines).toEqual([{ productCode: "ABRAA1B", container: null, quantity: 100, boxQuantity: 2, unitsPerBox: 50 }]);

    await expect(page).toHaveURL(/\/invoices\/15$/);
    // Acotado al encabezado: el aviso «Factura … registrada.» repite el texto.
    await expect(page.getByRole("heading", { name: "Factura F-2024-0158" })).toBeVisible();
  });

  test("contar una línea envía solo las piezas y muestra la línea ya contada", async ({ page }) => {
    let current = invoice([LINE_PENDING]);
    await mockList(page, current.lines);
    await page.route("**/v1/invoices/15", (route) => route.fulfill({ json: current }));
    await page.route("**/v1/invoices/15/lines/88/capture", (route) => {
      current = invoice([{ ...LINE_PENDING, physicalQuantity: "80.000", difference: "-20.000", status: "DISCREPANCY", capturedByName: "Capturista" }], "IN_PROGRESS");
      return route.fulfill({ json: current.lines[0] });
    });
    await signIn(page);
    await openReceiving(page);

    await page.getByText("F-2024-0158").first().click();
    await expect(page.getByText("Factura F-2024-0158")).toBeVisible();

    await page.getByRole("button", { name: "Contar" }).click();
    await page.locator('input[name="physicalQuantity"]').fill("80");

    const request = page.waitForRequest((req) => req.url().includes("/lines/88/capture"));
    await page.getByRole("button", { name: "Guardar conteo" }).click();
    expect((await request).postDataJSON()).toEqual({ physicalQuantity: 80 });

    // Una diferencia pide aprobación: la línea queda en discrepancia y la factura lo dice.
    await expect(page.getByText("DISCREPANCY").first()).toBeVisible();
  });

  test("la API rechaza la aprobación de quien capturó, y la web muestra el motivo", async ({ page }) => {
    const discrepancy = { ...LINE_PENDING, physicalQuantity: "80.000", difference: "-20.000", status: "DISCREPANCY", capturedByName: "Capturista" };
    await mockList(page, [discrepancy]);
    await page.route("**/v1/invoices/15", (route) => route.fulfill({ json: invoice([discrepancy], "IN_PROGRESS") }));
    await page.route("**/v1/invoices/15/approve", (route) =>
      route.fulfill({
        status: 403,
        json: { code: "SEGREGATION_OF_DUTIES", message: "Quien capturó una línea no puede aprobar su discrepancia.", details: { lines: "88" } },
      }),
    );
    await signIn(page);
    await openReceiving(page);

    await page.getByText("F-2024-0158").first().click();
    await page.getByRole("button", { name: "Aprobar discrepancias" }).click();
    await page.locator('select[name="adjustmentReason"]').selectOption("SUPPLIER_ERROR");

    const request = page.waitForRequest((req) => req.url().includes("/approve"));
    await page.getByRole("button", { name: "Aprobar", exact: true }).click();
    // La decisión lleva el motivo y no lleva nota: no se inventa un texto que nadie escribió.
    expect((await request).postDataJSON()).toEqual({ adjustmentReason: "SUPPLIER_ERROR" });

    await expect(page.getByText("Quien capturó una línea no puede aprobar su discrepancia.")).toBeVisible();
  });

  test("asignarse una línea pendiente envía la asignación sin cuerpo y la línea queda a nombre del usuario", async ({ page }) => {
    let current = invoice([LINE_PENDING]);
    await mockList(page, current.lines);
    await page.route("**/v1/invoices/15", (route) => route.fulfill({ json: current }));
    await page.route("**/v1/invoices/15/lines/88/assign", (route) => {
      current = invoice([{ ...LINE_PENDING, status: "ASSIGNED", capturedByName: null }], "IN_PROGRESS");
      return route.fulfill({ json: current.lines[0] });
    });
    await signIn(page);
    await openReceiving(page);

    await page.getByText("F-2024-0158").first().click();
    const request = page.waitForRequest((req) => req.url().endsWith("/lines/88/assign"));
    await page.getByRole("button", { name: "Asignarme" }).click();
    // Sin userId: la API asigna a quien llama.
    expect((await request).postDataJSON()).toEqual({});
    await expect(page.getByText("ASSIGNED").first()).toBeVisible();
  });
});
