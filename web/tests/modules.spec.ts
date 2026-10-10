import { expect, test, type Page, type Request } from "@playwright/test";
import { openMenu } from "./support/menu";
import { mockHomeSummary } from "./support/home";

/**
 * Las pantallas de los módulos de lectura, **con la API simulada**.
 *
 * Como en `catalog.spec.ts`: aquí se prueba la interfaz y la petición exacta que
 * hace la web. El comportamiento de la API está en los tests de Kotlin.
 *
 * Se navega con el menú y no con `goto`: recargar la página cierra la sesión (los
 * tokens viven solo en memoria, por decisión de seguridad).
 */

const PERMISSIONS = [
  "inventory.view",
  "kardex.view",
  "invoice.view",
  "order.view",
  "transfer.view",
  "label.queue_view",
  "audit.view",
];

const USER = {
  id: 1,
  username: "jefa",
  name: "Jefa de almacén",
  roles: ["ADMIN"],
  permissions: PERMISSIONS,
  scopes: { "inventory.view": "ALL" },
  warehouses: [1],
  mustChangePassword: false,
};

const SESSION = { accessToken: "token-de-prueba", refreshToken: "refresh-de-prueba", expiresIn: 900, user: USER };

const page1 = <T>(items: T[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });

async function signIn(page: Page) {
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));

  await page.goto("/");
  await page.locator('input[name="username"]').fill("jefa");
  await page.locator('input[name="password"]').fill("lo-que-sea");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Home", exact: true })).toBeVisible();
}

/** Entra al módulo desde el menú: un clic dentro de la app, sin recargar. */
async function openFromMenu(page: Page, label: string) {
  await openMenu(page, label);
}

/** La petición que hizo la web a `pathname`, con sus parámetros. */
function requestTo(page: Page, pathname: string): Promise<Request> {
  return page.waitForRequest((req) => new URL(req.url()).pathname === pathname);
}

test.beforeEach(async ({ page }) => mockHomeSummary(page));

test.describe("módulos de lectura (API simulada)", () => {
  test("Stock pide /inventory/stock y pinta la línea que devuelve", async ({ page }) => {
    await page.route("**/v1/inventory/stock*", (route) =>
      route.fulfill({
        json: page1([
          {
            productId: 1,
            productCode: "ABRAA1B",
            productName: "ABRAZADERA DE ARRANQUE",
            locationId: 10,
            locationDisplayName: "A-01-02",
            warehouseCode: "BOD1",
            quantity: 12,
          },
        ]),
      }),
    );
    await signIn(page);

    const request = requestTo(page, "/v1/inventory/stock");
    await openFromMenu(page, "Stock");

    await expect(page.getByText("How much there is of each product and in which location.")).toBeVisible();
    await expect(page.getByText("ABRAZADERA DE ARRANQUE")).toBeVisible();
    await request;
  });

  test("Receiving pide las facturas y la bandeja de aprobación", async ({ page }) => {
    await page.route("**/v1/invoices?*", (route) =>
      route.fulfill({
        json: page1([
          {
            id: 7,
            invoiceNumber: "FAC-1001",
            receiptDate: "2026-10-09",
            status: "OPEN",
            totalLines: 3,
            pendingApprovalLines: 0,
          },
        ]),
      }),
    );
    // Va después de la general: Playwright da prioridad a la última ruta registrada.
    await page.route("**/v1/invoices/pending-approval*", (route) => route.fulfill({ json: { items: [] } }));
    await signIn(page);

    const request = requestTo(page, "/v1/invoices");
    await openFromMenu(page, "Receiving");

    await expect(page.getByText("Received invoices and those waiting for a supervisor's approval.")).toBeVisible();
    await expect(page.getByText("FAC-1001")).toBeVisible();
    await request;
  });

  test("Fulfillment pide las órdenes y pinta su estado de exportación", async ({ page }) => {
    await page.route("**/v1/orders?*", (route) =>
      route.fulfill({
        json: page1([
          {
            id: 3,
            orderNumber: "ORD-0003",
            status: "PICKING",
            totalLines: 2,
            packagedLines: 1,
            exportStatus: "PENDING",
            exportLastError: null,
          },
        ]),
      }),
    );
    await signIn(page);

    const request = requestTo(page, "/v1/orders");
    await openFromMenu(page, "Fulfillment");

    await expect(page.getByText("ORD-0003")).toBeVisible();
    await request;
  });

  test("Transfers muestra los traslados y los que van en camino", async ({ page }) => {
    await page.route("**/v1/transfers?*", (route) =>
      route.fulfill({
        json: page1([
          {
            id: 5,
            code: "TR-0005",
            fromWarehouseCode: "BOD1",
            toWarehouseCode: "BOD2",
            status: "IN_TRANSIT",
            lineCount: 1,
            totalUnits: 4,
            dispatchedByName: "Jefa de almacén",
          },
        ]),
      }),
    );
    // La específica (in-transit) va al final: «transfers?*» también casa con «transfers/in-transit».
    await page.route("**/v1/transfers/in-transit*", (route) =>
      route.fulfill({
        json: [{ id: 5, code: "TR-0005", fromWarehouseCode: "BOD1", toWarehouseCode: "BOD2", daysInTransit: 2 }],
      }),
    );
    await signIn(page);

    const request = requestTo(page, "/v1/transfers");
    await openFromMenu(page, "Transfers");

    await expect(page.getByText("TR-0005").first()).toBeVisible();
    await request;
  });

  test("Labels pide la cola en /v1/labels/jobs, no en las plantillas", async ({ page }) => {
    // Regresión: la web llamó a /v1/label-templates/jobs y la API respondió 400, porque
    // el {id} del detalle de plantillas absorbía la palabra «jobs».
    await page.route("**/v1/labels/jobs?*", (route) =>
      route.fulfill({
        json: page1([
          {
            id: 9,
            templateCode: "ETQ-STD",
            copies: 2,
            status: "PRINTED",
            attempts: 1,
            createdAt: "2026-10-09T15:00:00Z",
            printedAt: "2026-10-09T15:01:00Z",
            lastError: null,
            reprintedFromId: null,
          },
        ]),
      }),
    );
    await signIn(page);

    const request = requestTo(page, "/v1/labels/jobs");
    await openFromMenu(page, "Labels");

    await expect(page.getByText("Labels requested, printed and failed.")).toBeVisible();
    await expect(page.getByText("ETQ-STD")).toBeVisible();
    await request;
  });

  test("Audit pide la bitácora y abre con su título", async ({ page }) => {
    await page.route("**/v1/audit/log?*", (route) => route.fulfill({ json: page1([]) }));
    await signIn(page);

    const request = requestTo(page, "/v1/audit/log");
    await openFromMenu(page, "Audit");

    await expect(page.getByText("Who did what, when and from where. Every write in the system appears here.")).toBeVisible();
    await request;
  });
});
