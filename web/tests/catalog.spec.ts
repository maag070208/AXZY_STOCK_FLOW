import { expect, test, type Page } from "@playwright/test";

/**
 * Las pantallas del catálogo, **con la API simulada**.
 *
 * Aquí no se prueba la API —eso lo hacen los tests de Kotlin, con la base real—,
 * sino la interfaz: que el menú se dibuje, que la tabla traduzca bien el contrato
 * de paginación y que los formularios abran. Con datos simulados se puede además
 * afirmar lo que la API real no deja afirmar sin ensuciar datos: qué petición
 * exacta hace la web.
 *
 * El estado de la API real está en `login.spec.ts`.
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
  await page.getByRole("button", { name: "Entrar" }).click();
  await expect(page.getByText("Estado del sistema y accesos del catálogo")).toBeVisible();
}

test.describe("catálogo (API simulada)", () => {
  test("el menú lateral se ve a 1280 px y lleva a Productos", async ({ page }) => {
    await signIn(page);

    // La prueba del §5 de web-react.md: el menú tiene que VERSE, no solo existir.
    const menuItem = page.getByText("Productos", { exact: true }).first();
    await expect(menuItem).toBeVisible();
    expect((await menuItem.boundingBox())?.width ?? 0).toBeGreaterThan(0);
  });

  test("la tabla traduce la paginación y pinta la página que devuelve la API", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) => route.fulfill({ json: { items: [] } }));

    // Se captura la consulta exacta: es donde vive el error de la página ±1.
    let productsQuery = "";
    await page.route("**/v1/products?*", async (route) => {
      productsQuery = new URL(route.request().url()).search;
      await route.fulfill({ json: PRODUCTS_PAGE });
    });

    await signIn(page);
    await page.getByText("Productos", { exact: true }).first().click();

    await expect(page.getByText("ABRAA1B")).toBeVisible();
    await expect(page.getByText("ABRAZADERA DE ARRANQUE")).toBeVisible();
    // La cantidad se muestra con el formato de la casa, no como número crudo.
    await expect(page.getByText("5,193")).toBeVisible();

    // El kit pide la página 1; la API recibe la 0. Sin la traducción, la primera
    // página saldría vacía y nadie sabría por qué.
    expect(productsQuery).toContain("page=0");
    expect(productsQuery).toContain("size=25");
    expect(productsQuery).not.toContain("page=1");
  });

  test("el alta de producto abre el formulario con el código editable", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) => route.fulfill({ json: { items: [] } }));
    await page.route("**/v1/products?*", (route) => route.fulfill({ json: PRODUCTS_PAGE }));

    await signIn(page);
    await page.getByText("Productos", { exact: true }).first().click();
    await page.getByRole("button", { name: "Nuevo producto" }).click();

    await expect(page.getByText("Nuevo producto", { exact: true }).last()).toBeVisible();
    await expect(page.locator('input[name="productCode"]')).toBeEnabled();
    await expect(page.locator('input[name="unitMeasure"]')).toHaveValue("PZA");
  });

  test("el listado de bodegas se dibuja sin paginar", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) =>
      route.fulfill({
        json: {
          items: [
            { id: 1, code: "PRINCIPAL", name: "Bodega principal", address: null, isDefault: true, active: true },
            { id: 2, code: "NORTE", name: "Bodega norte", address: null, isDefault: false, active: true },
          ],
        },
      }),
    );

    await signIn(page);
    await page.getByText("Bodegas", { exact: true }).first().click();

    await expect(page.getByText("PRINCIPAL", { exact: true })).toBeVisible();
    await expect(page.getByText("Bodega norte", { exact: true })).toBeVisible();
  });

  test("la importación muestra el loader de marca mientras la API trabaja", async ({ page }) => {
    await page.route("**/v1/warehouses*", (route) => route.fulfill({ json: { items: [] } }));
    await page.route("**/v1/products?*", (route) => route.fulfill({ json: PRODUCTS_PAGE }));

    // La respuesta se retiene a propósito: sin una espera real no habría nada que
    // mirar, y el loader podría no llegar a pintarse nunca sin que se note.
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("**/v1/products/import", async (route) => {
      await held;
      await route.fulfill({ json: IMPORT_REPORT });
    });

    await signIn(page);
    await page.getByText("Productos", { exact: true }).first().click();
    await page.getByRole("button", { name: "Importar" }).click();
    await page.setInputFiles('input[type="file"]', {
      name: "catalogo.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("externalId,productCode,name,unitMeasure,stock\n1,ABRAA1B,ABRAZADERA,PZA,10"),
    });
    await page.getByRole("button", { name: "Simular" }).click();

    await expect(page.getByRole("status")).toContainText("Procesando el archivo…");

    release();
    await expect(page.getByText("Simulación: no se escribió nada")).toBeVisible();
  });

  test("una cuenta sin permisos de catálogo no ve el menú de productos", async ({ page }) => {
    await page.route("**/v1/auth/login", (route) =>
      route.fulfill({ json: { ...SESSION, user: { ...USER, permissions: ["inventory.view"] } } }),
    );
    await page.route("**/v1/auth/me", (route) =>
      route.fulfill({ json: { ...USER, permissions: ["inventory.view"] } }),
    );

    await page.goto("/");
    await page.locator('input[name="username"]').fill("operador");
    await page.locator('input[name="password"]').fill("lo-que-sea");
    await page.getByRole("button", { name: "Entrar" }).click();

    await expect(page.getByText("Estado del sistema y accesos del catálogo")).toBeVisible();
    await expect(page.getByText("Productos", { exact: true })).toHaveCount(0);
  });
});
