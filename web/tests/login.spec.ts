import { expect, test } from "@playwright/test";
import { E2E } from "./support/env";

/**
 * La entrada, contra la **API de verdad**.
 *
 * No hay una prueba que «simule» la entrada: lo que se quiere saber es si la web
 * y la API se entienden. Las credenciales salen del `.env`, y la prueba **falla**
 * si faltan en lugar de saltarse: una prueba saltada cuenta como aprobada y ya
 * ocultó huecos antes (docs/pruebas/estrategia-pruebas.md).
 */
test.describe("entrada", () => {
  test("la API responde antes de empezar", async ({ request }) => {
    const health = await request.get(`${E2E.apiUrl}/v1/health`);

    expect(
      health.ok(),
      `La API no responde en ${E2E.apiUrl}. Levántala con: docker compose up -d`,
    ).toBeTruthy();
  });

  test("la marca se sirve y el manifiesto dice el nombre del producto", async ({ request }) => {
    // Los archivos que `index.html` declara tienen que existir de verdad: un
    // favicon que no se sirve no rompe nada y nadie lo nota.
    for (const asset of ["/favicon.svg", "/favicon.ico", "/apple-touch-icon.png", "/icon-192.png", "/site.webmanifest"]) {
      const response = await request.get(asset);
      expect(response.ok(), `${asset} no se sirve`).toBeTruthy();
    }

    const manifest = await (await request.get("/site.webmanifest")).json();
    expect(manifest.short_name).toBe("StockFlow");
    expect(manifest.icons.length).toBeGreaterThanOrEqual(4);
  });

  test("la pantalla de entrada se dibuja", async ({ page }) => {
    await page.goto("/");

    await expect(page.getByText("Gestión de almacén")).toBeVisible();
    await expect(page.locator('input[name="username"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();

    // Con los campos vacíos el botón está deshabilitado a propósito: no se puede
    // mandar una petición que la API va a rechazar por falta de campo.
    const entrar = page.getByRole("button", { name: "Entrar" });
    await expect(entrar).toBeDisabled();
    await page.locator('input[name="username"]').fill("alguien");
    await page.locator('input[name="password"]').fill("algo");
    await expect(entrar).toBeEnabled();
  });

  test("con credenciales incorrectas muestra el mensaje de la API", async ({ page }) => {
    await page.goto("/");

    /*
     * Un nombre DISTINTO en cada corrida. El bloqueo por intentos es por nombre
     * de usuario (I2: cinco fallos en 15 minutos), así que con un nombre fijo la
     * sexta corrida recibiría `ACCOUNT_LOCKED` —que es correcto— y la prueba
     * fallaría por su propia repetición, no por un defecto.
     */
    await page.locator('input[name="username"]').fill(`no-existe-${Date.now()}`);
    await page.locator('input[name="password"]').fill("contrasena-incorrecta-123");
    await page.getByRole("button", { name: "Entrar" }).click();

    // El texto lo escribe la API. La web no lo inventa: si lo inventara, mentiría
    // en cuanto la cuenta estuviera bloqueada, que es otro caso con otro remedio.
    await expect(page.getByText("Usuario o contraseña incorrectos.")).toBeVisible();
    await expect(page).toHaveURL(/#\/login$/);
  });

  test("con las credenciales del entorno entra, y la API decide la pantalla", async ({ page }) => {
    expect(
      E2E.adminPassword,
      "Falta ADMIN_BOOTSTRAP_PASSWORD en el .env del repositorio: sin credenciales no se puede probar la entrada.",
    ).not.toBe("");

    await page.goto("/");
    await page.locator('input[name="username"]').fill(E2E.adminUser);
    await page.locator('input[name="password"]').fill(E2E.adminPassword);
    await page.getByRole("button", { name: "Entrar" }).click();

    /*
     * Dos desenlaces, y los dos los decide la API, no la web:
     *  - contraseña temporal → la pantalla de cambio, sin menú (I8);
     *  - contraseña ya cambiada → el armazón, con «Inicio».
     * La prueba acepta los dos a propósito: cuál toca depende del estado de la
     * cuenta, y afirmar uno solo la volvería frágil sin ganar nada.
     */
    await expect(
      page.getByText(/Inicio|Cambio de contraseña/),
    ).toBeVisible();
  });
});
