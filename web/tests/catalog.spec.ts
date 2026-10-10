import { expect, test, type Page } from "@playwright/test";
import { expandMenu, openMenu } from "./support/menu";
import { mockHomeSummary } from "./support/home";

/**
 * The catalog screens, **with the simulated API**.
 *
 * The API is not tested here —the Kotlin tests do that, against the real database—,
 * but the interface: that the menu is drawn, that the table translates the contract well
 * of pagination and that the forms open. With simulated data one can also
 * to assert what the real API does not let you assert without dirtying data: what request
 * exact is what the web does.
 *
 * The real API state is in `login.spec.ts`.
 */

const USER = {
  id: 1,
  username: "jefa",
  name: "Jefa de almacén",
  roles: ["ADMIN"],
  permissions: [
    "product.view",
    "product.create",
    "product.edit",
    "product.delete",
    "product.import",
    "location.view",
    "location.create",
    "location.edit",
    "location.delete",
    "warehouse.view",
    "warehouse.create",
    "warehouse.edit",
  ],
  scopes: { "product.view": "ALL" },
  warehouses: [1],
  mustChangePassword: false,
};

const SESSION = { accessToken: "token-de-prueba", refreshToken: "refresh-de-prueba", expiresIn: 900, user: USER };

const PRODUCTS_PAGE = {
  items: [
    {
      id: 1,
      productCode: "ABRAA1B",
      name: "ABRAZADERA DE ARRANQUE",
      description: null,
      unitMeasure: "PZA",
      externalId: 7,
      active: true,
      stock: 5193,
    },
    {
      id: 2,
      productCode: "ABRAA1C",
      name: "ABRAZADERA DESCONTINUADA",
      description: null,
      unitMeasure: "PZA",
      externalId: null,
      active: false,
      stock: 0,
    },
  ],
  page: 0,
  size: 25,
  totalItems: 2,
  totalPages: 1,
};

const IMPORT_REPORT = {
  dryRun: true,
  totalRows: 2,
  accepted: 2,
  rejected: 0,
  totalStock: 15,
  zeroStockCount: 0,
  unitMeasures: ["PZA"],
  rejections: [],
  created: 0,
  updated: 0,
  skipped: 0,
  persisted: 0,
  stockPersisted: false,
};

async function signIn(page: Page) {
  await page.route("**/v1/auth/login", (route) => route.fulfill({ json: SESSION }));
  await page.route("**/v1/auth/me", (route) => route.fulfill({ json: USER }));

  await page.goto("/");
  await page.locator('input[name="username"]').fill("jefa");
  await page.locator('input[name="password"]').fill("lo-que-sea");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "Home", exact: true })).toBeVisible();
}

test.beforeEach(async ({ page }) => mockHomeSummary(page));

test.describe("catalog (simulated API)", () => {
  test("the sidebar menu is visible at 1280 px and leads to Products", async ({ page }) => {
    await signIn(page);

    // The §5 test of web-react.md: the menu has to be SEEN, not just exist.
    // The menu hides itself: it unfolds when the cursor approaches, and then the label has to be visible.
    const sidebar = await expandMenu(page);
    const menuItem = sidebar.getByText("Products", { exact: true });
    await expect(menuItem).toBeVisible();
    expect((await menuItem.boundingBox())?.width ?? 0).toBeGreaterThan(0);
  });

  test("the table translates pagination and renders the page the API returns", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) => route.fulfill({ json: { items: [] } }));

    // The exact query is captured: it is where the ±1 page error lives.
    let productsQuery = "";
    await page.route("**/v1/products?*", async (route) => {
      productsQuery = new URL(route.request().url()).search;
      await route.fulfill({ json: PRODUCTS_PAGE });
    });

    await signIn(page);
    await openMenu(page, "Products");

    await expect(page.getByText("ABRAA1B")).toBeVisible();
    await expect(page.getByText("ABRAZADERA DE ARRANQUE")).toBeVisible();
    // The quantity is shown with the house format, not as a raw number.
    await expect(page.getByText("5,193")).toBeVisible();

    // The kit requests page 1; the API receives 0. Without the translation, the first
    // page would come out empty and nobody would know why.
    expect(productsQuery).toContain("page=0");
    expect(productsQuery).toContain("size=25");
    expect(productsQuery).not.toContain("page=1");
  });

  test("creating a product opens the form with an editable code", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) => route.fulfill({ json: { items: [] } }));
    await page.route("**/v1/products?*", (route) => route.fulfill({ json: PRODUCTS_PAGE }));

    await signIn(page);
    await openMenu(page, "Products");
    await page.getByRole("button", { name: "New product" }).click();

    await expect(page.getByText("New product", { exact: true }).last()).toBeVisible();
    await expect(page.locator('input[name="productCode"]')).toBeEnabled();
    await expect(page.locator('input[name="unitMeasure"]')).toHaveValue("PZA");
  });

  test("the warehouse list renders without pagination", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) =>
      route.fulfill({
        json: {
          items: [
            { id: 1, code: "MAIN", name: "Warehouse principal", address: null, isDefault: true, active: true },
            { id: 2, code: "NORTE", name: "Warehouse norte", address: null, isDefault: false, active: true },
          ],
        },
      }),
    );

    await signIn(page);
    await openMenu(page, "Warehouses");

    await expect(page.getByText("MAIN", { exact: true })).toBeVisible();
    await expect(page.getByText("Warehouse norte", { exact: true })).toBeVisible();
  });

  test("the import shows the brand loader while the API works", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) => route.fulfill({ json: { items: [] } }));
    await page.route("**/v1/products?*", (route) => route.fulfill({ json: PRODUCTS_PAGE }));

    // The response is held on purpose: without a real wait there would be nothing to
    // look at, and the loader could never be painted without anyone noticing.
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("**/v1/products/import", async (route) => {
      await held;
      await route.fulfill({ json: IMPORT_REPORT });
    });

    await signIn(page);
    await openMenu(page, "Products");
    await page.getByRole("button", { name: "Import" }).click();
    await page.setInputFiles('input[type="file"]', {
      name: "catalogo.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("externalId,productCode,name,unitMeasure,stock\n1,ABRAA1B,ABRAZADERA,PZA,10"),
    });
    await page.getByRole("button", { name: "Preview" }).click();

    await expect(page.getByRole("status")).toContainText("Processing the file…");

    release();
    await expect(page.getByText("Dry run: nothing was written")).toBeVisible();
  });

  test("an account without catalog permissions does not see the products menu", async ({ page }) => {
    await page.route("**/v1/auth/login", (route) =>
      route.fulfill({ json: { ...SESSION, user: { ...USER, permissions: ["inventory.view"] } } }),
    );
    await page.route("**/v1/auth/me", (route) =>
      route.fulfill({ json: { ...USER, permissions: ["inventory.view"] } }),
    );

    await page.goto("/");
    await page.locator('input[name="username"]').fill("operador");
    await page.locator('input[name="password"]').fill("lo-que-sea");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByRole("heading", { name: "Home", exact: true })).toBeVisible();
    await expect(page.getByText("Products", { exact: true })).toHaveCount(0);
  });
});
