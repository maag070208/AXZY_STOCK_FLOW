import { expect, test, type Page } from "@playwright/test";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * E2E DE NAVEGADOR — la web contra un backend REAL
 *
 * **QUÉ AÑADE ESTO QUE NO AÑADAN LOS OTROS TESTS.**
 *
 * El backend tiene 15 e2e de API que llaman a las rutas con un token de verdad.
 * `tsc` y `eslint` validan los tipos y las URLs *en el código*. Ninguno de los
 * tres prueba lo que pasa **en un navegador**: que el formulario guarde el token,
 * que el guard de la ruta deje pasar, que `React` monte la tabla y que los valores
 * lleguen a la pantalla.
 *
 * Y hay un motivo concreto, de este proyecto: **un cambio de nombre de campo en la
 * API no lo detecta ni `tsc` ni `eslint`.** Cuando los campos de la reconciliación
 * se llamaban `totalEnBd`/`totalEsperado`/`diferencia` y el tipo compartido
 * declaraba `ledgerNet`/`projected`, las dos partes compilaban y **la pantalla
 * pintaba `undefined`**. Eso solo se ve ejecutando.
 *
 * ── LAS CREDENCIALES VIENEN DEL SCRIPT ─────────────────────────────────────
 *
 * `scripts/e2e-web.sh` recrea una base dedicada, siembra un administrador con
 * contraseña CONOCIDA y arranca los dos servidores. Aquí se consumen. Si faltan
 * las variables, la suite **falla con un mensaje que dice qué ejecutar** en lugar
 * de saltarse en silencio: un e2e que se salta solo es un e2e que no vigila.
 * ═══════════════════════════════════════════════════════════════════════════
 */

const USUARIO = process.env.E2E_USERNAME;
const PASSWORD = process.env.E2E_PASSWORD;
const API_URL = process.env.E2E_API_URL ?? "http://127.0.0.1:8098";

/** Clave real del almacén de sesión (`tokenStorage.ts`). */
const CLAVE_SESION = "almacen_integral_auth_v1";

test.beforeAll(() => {
  if (!USUARIO || !PASSWORD) {
    throw new Error(
      "Faltan E2E_USERNAME y E2E_PASSWORD.\n" +
        "No se ejecuta con `playwright test` a secas: el entorno lo levanta\n" +
        "  ./scripts/e2e-web.sh\n" +
        "que crea la base, siembra el administrador y arranca backend y web.",
    );
  }
});

/** Rellena el formulario de acceso y espera a salir de `/login`. */
async function iniciarSesion(page: Page, usuario: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel(/usuario/i).fill(usuario);
  await page.getByLabel(/contraseña/i).fill(password);
  // Por NOMBRE ACCESIBLE, no por `getByRole("button")` a secas: el campo de
  // contraseña lleva su propio botón para mostrar el texto, así que el selector
  // genérico resuelve a DOS elementos y Playwright falla por ambigüedad. El
  // botón de envío tiene `aria-label` —"Entrar"—, que sale del diccionario de
  // la interfaz y es lo que un lector de pantalla anuncia.
  await page.getByRole("button", { name: /entrar/i }).click();
}

test.describe("la web funciona contra el backend real", () => {
  test("la raíz sin sesión lleva al acceso en lugar de dejar pasar", async ({ page }) => {
    // Es la primera línea de defensa: el guard tiene que redirigir ANTES de que
    // ninguna pantalla pida datos con un token que no existe.
    await page.goto("/");
    await expect(page).toHaveURL(/\/login/);
  });

  test("el acceso rechaza credenciales malas y NO guarda sesión", async ({ page }) => {
    await iniciarSesion(page, USUARIO!, "esta-no-es-la-contrasena");

    // El mensaje sale del catálogo de errores del contrato
    // (`INVALID_CREDENTIALS` → «Usuario o contraseña incorrectos.»), no de un
    // texto inventado por la pantalla.
    await expect(page.getByText(/usuario o contraseña incorrectos/i)).toBeVisible();

    // Y lo que más importa: **seguimos en `/login` y no hay token**. Una pantalla
    // que muestra el error pero guarda la sesión es un fallo silencioso.
    await expect(page).toHaveURL(/\/login/);
    const sesion = await page.evaluate((clave) => window.localStorage.getItem(clave), CLAVE_SESION);
    expect(sesion).toBeNull();
  });

  test.describe("con sesión de administrador", () => {
    test.beforeEach(async ({ page }) => {
      await iniciarSesion(page, USUARIO!, PASSWORD!);
      // La app usa un router por HASH, así que la ruta real vive después del `#`:
      // en el acceso es `<origen>/login#/login` y tras entrar queda
      // `<origen>/login#/`. Afirmar «la URL no contiene /login» sería falso
      // —el origen lo lleva— y es el tipo de aserción que se ajusta hasta que
      // deja de comprobar nada. Se afirma sobre el FRAGMENTO, que sí distingue.
      await expect(page).not.toHaveURL(/login#\/login/);
    });

    test("el acceso guarda la sesión y sale de la pantalla de login", async ({ page }) => {
      const sesion = await page.evaluate((clave) => window.localStorage.getItem(clave), CLAVE_SESION);
      expect(sesion, "el token tiene que quedar persistido: es lo que sobrevive a una recarga").toBeTruthy();

      const guardado = JSON.parse(sesion!) as { accessToken?: string };
      expect(guardado.accessToken).toBeTruthy();
    });

    test("recargar la página CONSERVA la sesión y la pantalla se pinta", async ({ page }) => {
      // **Este test cubre el defecto más caro que encontró el e2e.** Recargar en
      // una ruta autenticada dejaba la aplicación **en blanco para siempre**: el
      // marcador `aria-busy` de `PrivateRoutes` se quedaba puesto porque el
      // arranque de la sesión no llegaba a guardar el usuario (ver el comentario
      // de `useSessionBootstrap`). Navegar dentro de la SPA no lo mostraba,
      // porque el estado ya estaba en memoria: **solo se veía recargando**, que
      // es justo lo que hace un operador de turno de noche a media operación.
      await page.goto("/#/reports");
      await expect(page.getByText(/el inventario cuadra/i)).toBeVisible({ timeout: 15_000 });

      // Y ahora, la recarga de verdad.
      await page.reload();

      // El marcador de carga NO puede quedarse: si aparece y no se va, la
      // pantalla está colgada.
      await expect(page.locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 15_000 });
      await expect(page.getByText(/el inventario cuadra/i)).toBeVisible({ timeout: 15_000 });
    });

    test("los reportes se pintan con los datos del servidor y sin campos indefinidos", async ({ page }) => {
      // La ruta va CON `#` DELANTE: la app usa un router por HASH (para que una
      // recarga no pida al servidor una ruta que no existe como archivo). Pedir
      // `/reports` a secas **carga la aplicación en la raíz y el test mira Inicio
      // en lugar del informe**, sin fallar: es un falso verde de manual. Lo
      // encontró el diagnóstico del e2e, no el test.
      //
      // Esta es LA PRUEBA del cambio de §9. El inventario de una base recién
      // sembrada cuadra, así que el veredicto sano es el estado esperado.
      await page.goto("/#/reports");

      await expect(page.getByText(/el inventario cuadra/i)).toBeVisible({ timeout: 15_000 });

      // `undefined` en pantalla es exactamente lo que producía el desajuste de
      // nombres entre el contrato y la ruta. Se comprueba que NO aparece.
      await expect(page.locator("body")).not.toContainText("undefined");
      await expect(page.locator("body")).not.toContainText("NaN");

      // El sello de tiempo del servidor tiene que verse: si el campo se llamara
      // distinto, estaría vacío.
      await expect(page.getByText(/comprobado el/i)).toBeVisible();
    });


    test("los traslados muestran la deuda visible y la invariante que la explica", async ({
      page,
    }) => {
      // ── La invariante del contrato §7, COMPROBADA EN LA INTERFAZ ─────────
      //
      // > `Σ stock_by_location + Σ stock_in_transit` se conserva en cada transición.
      //
      // La prueba de esa invariante con números está en el backend
      // (`TransferInvariantTest`). **Lo que se comprueba aquí es que la pantalla
      // la haga VISIBLE**: que un traslado despachado aparezca como deuda en
      // tránsito, con su responsable y sus días, en lugar de desaparecer.
      //
      // Es la diferencia entre un sistema que conserva la invariante y uno que
      // además la cuenta: si la pantalla no la enseñara, la mercancía del camión
      // estaría viva en la base y nadie la perseguiría.
      await page.goto("/#/transfers");

      // Con una base recién sembrada no hay nada despachado, y el estado sano se
      // dice explícitamente en lugar de dejar una tabla vacía.
      await expect(page.getByText(/no hay mercancía en tránsito/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.locator("body")).not.toContainText("undefined");
      await expect(page.locator("body")).not.toContainText("NaN");

      // Y la página dice QUÉ SIGNIFICA que no haya nada, que es lo que el usuario
      // necesita para confiar en el número.
      await expect(page.getByText(/todo lo despachado se ha recibido/i)).toBeVisible();
    });

    test("un traslado despachado aparece como deuda en tránsito, con su responsable", async ({
      page,
      request,
    }) => {
      // Se fabrica el escenario por SQL y se opera por la API, que es lo que se
      // está probando.
      const auth = await pedirToken(request);
      await prepararTraslado();

      const alta = await request.post(`${API_URL}/v1/transfers`, {
        headers: auth,
        data: {
          fromWarehouseId: 1,
          toWarehouseId: await idBodegaSucursal(),
          lines: [{ productCode: "E2EW1", quantity: "25.000" }],
        },
      });
      expect(alta.status(), "el alta de traslado tiene que ser 201").toBe(201);
      const cuerpoAlta = (await alta.json()) as { transfer: { id: number; status: string } };
      // Se comprueba que nace en `DRAFT`: es el estado desde el que la interfaz
      // ofrece el botón de despachar. Si naciera en otro, el botón no aparecería y
      // el test fallaría con un «no encontró el botón» que no diría por qué.
      expect(cuerpoAlta.transfer.status).toBe("DRAFT");

      await page.goto("/#/transfers");
      await expect(page.getByText(/no hay mercancía en tránsito/i)).toBeVisible({ timeout: 15_000 });

      // Despachar por la interfaz: es el acto con responsable.
      await page.getByRole("button", { name: /^despachar$/i }).first().click();

      // **La mercancía NO desaparece: pasa a la sección de deuda visible.** Si la
      // pantalla la hubiera borrado, el usuario creería que salió del inventario.
      await expect(page.getByText(/1 traslado\(s\) en camino/i)).toBeVisible({ timeout: 15_000 });

      // **El traslado aparece DOS veces, y es correcto**: una en la sección de
      // deuda visible y otra en el listado completo. El `getByText` genérico
      // resuelve a dos elementos y Playwright falla por ambigüedad — le pasó a
      // este test. Se pide la fila de la tabla de tránsito, que es la que importa.
      const enCamino = page.locator("table").first();
      await expect(enCamino.getByRole("cell", { name: /^TR-/ })).toBeVisible();
      await expect(page.locator("body")).not.toContainText("undefined");

      // Y ahora se puede recibir, que es lo que cierra la deuda.
      await page.getByRole("button", { name: /^recibir$/i }).first().click();

      // Se espera al AVISO de éxito, que es la señal de que el servidor aceptó.
      //
      // **El panel de error se busca por su TEXTO y no por `role="alert"`:** la
      // sección de deuda visible del tránsito también es `role="alert"` cuando hay
      // traslados en camino, así que el selector genérico apuntaba a dos sitios y
      // hacía creer que había un error. Le pasó a este test.
      await expect(page.getByText(/traslado recibido\./i)).toBeVisible({ timeout: 15_000 });
      // Y con la deuda cerrada, el estado vuelve a decir que no hay nada en camino.
      await expect(page.getByText(/no hay mercancía en tránsito/i)).toBeVisible({ timeout: 15_000 });
    });


    test("el surtido muestra el pendiente y el estado del ERP, sin inventar datos", async ({
      page,
    }) => {
      // ── La pantalla de surtido, contra la API REAL ────────────────────────
      //
      // Con la base recién sembrada no hay órdenes, y el estado sano se dice en
      // lugar de dejar una tabla vacía. **Lo que se comprueba es que la petición
      // llegue y que la pantalla sepa leer la respuesta**: cuando esta página era
      // una maqueta no hacía ninguna petición, y cuando los tipos estaban mal
      // —`quantity` como número— la tabla habría pintado `NaN`.
      await page.goto("/#/orders");

      await expect(page.getByText(/todavía no hay órdenes de surtido/i)).toBeVisible({
        timeout: 15_000,
      });
      await expect(page.locator("body")).not.toContainText("NaN");
      await expect(page.locator("body")).not.toContainText("undefined");

      // El filtro de estado existe y no ofrece estados inventados: solo los del
      // contrato. Un filtro con un estado que la API no acepta daría `400` al
      // usarlo y el usuario no sabría por qué.
      const filtro = page.locator("#filtro-estado");
      await expect(filtro).toBeVisible();
      await expect(filtro.locator("option")).toHaveCount(6);
    });

    test("una orden real aparece con su pendiente y SIN botón de confirmar en el listado", async ({
      page,
      request,
    }) => {
      const autenticacion = await pedirToken(request);
      await prepararProductoConStock("E2EO1", "Orden E2E");

      // Se crea por la API: la pantalla de creación no existe todavía.
      const alta = await request.post(`${API_URL}/v1/orders`, {
        headers: autenticacion,
        data: {
          concept: "Surtido E2E",
          lines: [{ productCode: "E2EO1", quantity: "10.000" }],
        },
      });
      expect(alta.status(), "el alta de orden tiene que ser 201").toBe(201);
      const creada = (await alta.json()) as { id: number; orderNumber: string; status: string };
      expect(creada.status).toBe("PENDING");

      await page.goto("/#/orders");
      await expect(page.getByText(creada.orderNumber)).toBeVisible({ timeout: 15_000 });

      // `0 / 1` líneas completas: nada empacado todavía.
      await expect(page.getByText("0 / 1")).toBeVisible();

      // **No hay botón de confirmar en el listado.** Confirmar descuenta stock de
      // todas las líneas y dispara la exportación: es un acto que va en el detalle,
      // donde se ven las líneas antes. Un botón en la fila se pulsa sin mirar.
      const enListado = page.locator("table");
      await expect(enListado.getByRole("button", { name: /confirmar/i })).toHaveCount(0);

      // Y el detalle SÍ muestra el pendiente, que es el número que decide.
      await page.getByRole("link", { name: /abrir/i }).first().click();
      await expect(page.getByText(/pendiente total/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText("10.000").first()).toBeVisible();
      await expect(page.locator("body")).not.toContainText("NaN");
      await expect(page.locator("body")).not.toContainText("undefined");
    });


    test("el alta de una orden funciona desde la interfaz y NO toca el inventario al crearla", async ({
      page,
    }) => {
      // ── El flujo completo de alta, por la interfaz ────────────────────────
      //
      // **Y la comprobación que importa:** crear una orden **no mueve el stock**.
      // El contrato lo dice —«no toca el inventario: el stock sale al EMPACAR y al
      // CONFIRMAR, que son actos con responsable»— y es lo que evita que un alta
      // por error descuadre el almacén. Se lee el stock antes y después.
      await prepararProductoConStock("E2EFL1", "Alta por interfaz");
      const stockAntes = await ejecutarSql(
        "SELECT COALESCE(SUM(quantity),0) FROM stock_by_location s JOIN products p ON p.id=s.product_id " +
          "WHERE p.product_code='E2EFL1'",
      );
      expect(stockAntes, "el fixture tiene que dejar existencias").toBe("100.000");

      await page.goto("/#/orders");
      await page.getByRole("button", { name: /nueva orden/i }).click();

      // Validación EN CLIENTE: una línea sin código no debe llegar al servidor.
      // `422 PRODUCT_NOT_FOUND` por un código vacío es un error que el usuario no
      // puede entender, porque él no escribió ningún código.
      await page.getByRole("button", { name: /crear orden/i }).click();
      await expect(page.getByText(/al menos una línea con su código de producto/i)).toBeVisible();

      await page.getByLabel(/código de cliente/i).fill("CLI-E2E");
      await page.getByLabel(/concepto de la orden/i).fill("Alta E2E");
      await page.getByLabel(/código del producto 1/i).fill("E2EFL1");

      // Cantidad inválida: se avisa antes de enviar.
      await page.getByLabel(/cantidad de la línea 1/i).fill("0");
      await page.getByRole("button", { name: /crear orden/i }).click();
      await expect(page.getByText(/no llevan una cantidad válida/i)).toBeVisible();

      // Y con datos válidos, se crea.
      await page.getByLabel(/cantidad de la línea 1/i).fill("12.5");
      await page.getByRole("button", { name: /crear orden/i }).click();

      await expect(page.getByText(/creada con 1 línea/i)).toBeVisible({ timeout: 15_000 });
      // El número lo generó el SERVIDOR con el formato del contrato.
      await expect(page.getByText(/^ORD-\d{4}-\d+$/).first()).toBeVisible();
      await expect(page.locator("body")).not.toContainText("NaN");
      await expect(page.locator("body")).not.toContainText("undefined");

      // ── LA COMPROBACIÓN QUE IMPORTA ────────────────────────────────────
      //
      // El stock NO se movió. Si el alta hubiera descontado, el descuadre
      // aparecería al cuadrar el almacén y se confundiría con una merma.
      const stockDespues = await ejecutarSql(
        "SELECT COALESCE(SUM(quantity),0) FROM stock_by_location s JOIN products p ON p.id=s.product_id " +
          "WHERE p.product_code='E2EFL1'",
      );
      expect(stockDespues, "crear una orden NO puede mover el inventario").toBe(stockAntes);
    });

    test("el empaque descuenta lo empacado y el pendiente baja en pantalla", async ({
      page,
      request,
    }) => {
      // ── LA REGLA DEL DESCUENTO, COMPROBADA EN LA INTERFAZ ─────────────────
      //
      // El contrato: `suppliedQuantity` es lo YA descontado; empacar descuenta lo
      // empacado y confirmar descuenta LO QUE FALTA. La prueba con números está en
      // el backend (`OrderStockDeductionTest`). **Aquí se comprueba que la pantalla
      // enseñe las dos cifras y el pendiente resultante**, que es lo que el
      // operador necesita para no descontar dos veces.
      const autenticacion = await pedirToken(request);
      await prepararProductoConStock("E2EO2", "Empaque E2E");

      const alta = await request.post(`${API_URL}/v1/orders`, {
        headers: autenticacion,
        data: { lines: [{ productCode: "E2EO2", quantity: "10.000" }] },
      });
      const creada = (await alta.json()) as { id: number };

      await page.goto(`/#/orders/${creada.id}`);
      await expect(page.getByText(/pendiente total/i)).toBeVisible({ timeout: 15_000 });

      // Se empacan 7 de 10.
      await page.getByLabel(/cantidad a empacar/i).fill("7");
      await page.getByRole("button", { name: /^empacar$/i }).first().click();

      // **El pendiente pasa a 3, y la pantalla lo dice.** Si el servidor hubiera
      // descontado 10, aquí saldría 0 y la orden se creería completa: el descuadre
      // se descubriría cuadrando el almacén, donde se confunde con una merma.
      await expect(page.getByText(/pendiente total: 3\.000/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText("7.000").first()).toBeVisible();
      await expect(page.locator("body")).not.toContainText("NaN");
    });

    test("las etiquetas muestran la cola y NO ofrecen marcar como impreso", async ({ page }) => {
      // ── La regla del legacy, que esta pantalla NO puede reintroducir ──────
      //
      // > «El legacy la marcaba sin esperar la respuesta, así que si la Zebra
      // > estaba apagada, la etiqueta se perdía para siempre.»
      //
      // Nadie desde la interfaz marca una etiqueta como impresa. **Se comprueba
      // que el botón NO exista**, porque añadirlo sería volver al defecto con una
      // persona en lugar de un temporizador.
      // Se limpia lo que otra prueba haya podido dejar: **las pruebas comparten
      // base y este test asumía que estaba vacía**. Cuando el test de la vista
      // previa pasó a ejecutarse antes, dejó su plantilla y este falló con un
      // «no encontró el texto» que no señalaba a su causa. La dependencia del
      // orden es del andamiaje, no del código.
      await ejecutarSql("DELETE FROM print_jobs; DELETE FROM label_templates;");

      await page.goto("/#/labels");

      // **Se pide el ENCABEZADO y no el texto suelto.** «cola de impresión»
      // aparece también en la descripción de la pantalla —«Plantillas ZPL, vista
      // previa y cola de impresión»—, así que `getByText` resuelve a dos elementos
      // y Playwright falla por ambigüedad. Le pasó a este test.
      await expect(page.getByRole("heading", { name: /cola de impresión/i })).toBeVisible({
        timeout: 15_000,
      });
      // La pantalla explica quién puede cerrar un trabajo.
      await expect(page.getByText(/solo la impresora puede/i)).toBeVisible();
      // Y sin plantillas ni trabajos, lo dice.
      await expect(page.getByText(/no hay trabajos de impresión todavía/i)).toBeVisible();
      await expect(page.getByText(/no hay ninguna plantilla/i)).toBeVisible();

      const cuerpo = page.locator("body");
      await expect(cuerpo).not.toContainText("NaN");
      await expect(cuerpo).not.toContainText("undefined");

      // **El botón prohibido no está**, en ninguna de sus formas.
      for (const prohibido of [/marcar como impres/i, /marcar impres/i, /imprimir ahora/i]) {
        await expect(page.getByRole("button", { name: prohibido })).toHaveCount(0);
      }
    });


    test("el alta de una plantilla de etiqueta valida el ZPL ANTES de guardarlo", async ({ page }) => {
      // ── La regla que protege el papel ─────────────────────────────────────
      //
      // Una plantilla a la que le falta `^XZ` **no da error en la impresora**:
      // deja la etiqueta en blanco o el trabajo en la cola. Se descubre gastando
      // papel, así que se valida al GUARDAR — y la interfaz avisa antes de enviar,
      // para no hacer perder el formulario relleno.
      await ejecutarSql("DELETE FROM print_jobs; DELETE FROM label_templates;");

      await page.goto("/#/labels");
      await page.getByRole("button", { name: /nueva plantilla/i }).click();

      await page.getByLabel(/código de la plantilla/i).fill("e2e-alta");
      await page.getByLabel(/nombre de la plantilla/i).fill("Alta E2E");

      // ZPL sin cierre: se avisa en la interfaz.
      await page.getByLabel(/zpl de la plantilla/i).fill("^XA^FD{{productCode}}^FS");
      await page.getByRole("button", { name: /crear plantilla/i }).click();
      await expect(page.getByText(/tiene que llevar '\^XA'/i)).toBeVisible({ timeout: 15_000 });

      // Con el cierre, se crea y aparece en el listado.
      await page.getByLabel(/zpl de la plantilla/i).fill("^XA^FD{{productCode}}^FS^XZ");
      await page.getByRole("button", { name: /crear plantilla/i }).click();

      await expect(page.getByText(/plantilla 'e2e-alta' creada/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole("cell", { name: "e2e-alta" })).toBeVisible();
      await expect(page.locator("body")).not.toContainText("NaN");
      await expect(page.locator("body")).not.toContainText("undefined");
    });

    test("una etiqueta a la que le faltan datos NO se previsualiza: dice qué falta", async ({
      page,
      request,
    }) => {
      // ── La regla del resolutor ZPL, vista desde la interfaz ───────────────
      //
      // Una plantilla con un campo obligatorio sin rellenar **no se resuelve**.
      // Las dos alternativas son peores: dejar el marcador imprime
      // `{{productCode}}` en la etiqueta del estante, y sustituirlo por vacío
      // imprime una etiqueta que parece correcta y no lo está.
      const autenticacion = await pedirToken(request);
      const plantilla = await request.post(`${API_URL}/v1/label-templates`, {
        headers: autenticacion,
        data: {
          code: "e2e-receipt",
          name: "Recibo E2E",
          zpl: "^XA^FD{{productCode}}^FS^XZ",
          size: "4x6",
          fieldSchema: {
            productCode: { type: "string", label: "Código", required: true },
          },
        },
      });
      expect(plantilla.status(), "la plantilla tiene que crearse").toBe(201);

      await page.goto("/#/labels");
      await expect(page.getByText("e2e-receipt")).toBeVisible({ timeout: 15_000 });

      // Se abre la vista previa y se pulsa «Generar ZPL» SIN escribir el campo.
      await page.getByRole("button", { name: /^ver$/i }).first().click();
      await page.getByRole("button", { name: /generar zpl/i }).click();

      // El error NOMBRA el campo que falta: sin el nombre, el usuario tendría que
      // adivinar cuál de los campos de la plantilla está vacío.
      await expect(page.getByText(/faltan datos para esta etiqueta/i)).toBeVisible({
        timeout: 15_000,
      });
      await expect(page.getByText(/productCode/).first()).toBeVisible();
      // Y NO se generó ningún ZPL.
      await expect(page.getByLabel(/zpl resuelto/i)).toHaveCount(0);

      // Al rellenarlo, sí se genera, y el lienzo viene calculado del servidor.
      await page.getByLabel(/^Código/).fill("ABRAA1B");
      await page.getByRole("button", { name: /generar zpl/i }).click();
      await expect(page.getByLabel(/zpl resuelto/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByLabel(/zpl resuelto/i)).toContainText("ABRAA1B");
      await expect(page.getByText(/101\.6/)).toBeVisible();
    });


    test("la bitácora enseña el CANAL y el permiso de un acceso DENEGADO real", async ({
      page,
    }) => {
      // ── La dimensión que justifica la pantalla ────────────────────────────
      //
      // El contrato vende la auditoría como la respuesta a «quién hizo qué». El
      // legacy no podía responderlo porque **no guardaba el canal ni la petición**.
      //
      // Y hubo un tiempo en que este proyecto tampoco: `audit_log` tenía el esquema
      // completo —44 columnas, particiones, cadena de hash— y **cero filas**.
      // `AuditWriter.recordDeniedAccessSafely` existía y **nadie la llamaba**. Se
      // conectó al verificador de permisos, y esta prueba es la que impide que
      // vuelva a quedar en cero.
      //
      // La entrada se fabrica por SQL con la MISMA función que usa el servidor
      // (`audit_log_record`), no con un INSERT a mano: así se prueba la pantalla
      // contra la forma real del dato, con su cadena de hash ya calculada.
      // **Con CASTS explícitos.** Los parámetros son de tipos enumerados
      // (`audit_channel`, `audit_level`, `audit_decision`, `audit_result`) y una
      // cadena suelta no los resuelve: PostgreSQL responde «no existe la función»
      // en lugar de un error de tipo, que es un mensaje que no señala a su causa.
      await ejecutarSql(`
        SELECT audit_log_record(
          (SELECT id FROM users ORDER BY id LIMIT 1),
          'intruso-e2e', 'Intruso E2E', ARRAY['viewer'],
          'WEB'::audit_channel, '10.1.2.3'::inet, 'Mozilla/5.0 e2e', 'navegador-e2e',
          'http_route', 'GET /v1/users', 'Listado de usuarios',
          'access.denied', 'ACCESS'::audit_level, NULL::jsonb, NULL::jsonb, NULL, NULL,
          'user.view', 'DENIED'::audit_decision, 'FORBIDDEN',
          'DENIED'::audit_result, NULL, NULL, NULL,
          NULL::uuid, NULL::uuid, NULL, 'req-e2e-auditoria');
      `);

      await page.goto("/#/audit");

      // La tabla de la bitácora. **No se busca la palabra «bitácora»**: está en la
      // navegación —colapsada— y en el título, así que `getByText` resuelve a
      // varios elementos y el primero está oculto.
      const tabla = page.locator("table").first();
      await expect(tabla).toBeVisible({ timeout: 15_000 });

      // Los encabezados que existen precisamente por el legacy.
      for (const columna of [/canal/i, /resultado/i, /nivel/i, /quién/i]) {
        await expect(page.getByRole("columnheader", { name: columna })).toBeVisible();
      }

      // **La acción denegada aparece.** Un sistema que niega sin dejar constancia
      // no puede responder a la única pregunta que importa después de un
      // incidente: cuántas veces se intentó y desde dónde.
      await expect(tabla.getByText("access.denied")).toBeVisible();
      await expect(tabla.getByText("user.view")).toBeVisible();

      await expect(page.locator("body")).not.toContainText("NaN");
      await expect(page.locator("body")).not.toContainText("undefined");

      // El detalle enseña el `requestId`, que cruza la entrada con su petición, y
      // el hash que permite verificar la cadena de integridad.
      await page.getByRole("button", { name: /^ver$/i }).first().click();
      await expect(page.getByText("req-e2e-auditoria")).toBeVisible();
      await expect(page.getByText(/hash/i).first()).toBeVisible();
    });

    test("el filtro de la bitácora filtra de verdad, y el total lo cuenta el servidor", async ({
      page,
    }) => {
      // Un filtro que el servidor IGNORA no filtra y no falla: devuelve todo, y el
      // usuario cree que no hay más registros de los que ve. Por eso se comprueba
      // que el número CAMBIA al filtrar.
      await page.goto("/#/audit");
      await expect(page.getByText(/entrada\(s\)/)).toBeVisible({ timeout: 15_000 });

      const totalConTodo = await page.getByText(/entrada\(s\)/).innerText();

      // Se filtra por una acción que no existe: el total tiene que caer a cero.
      await page.getByLabel(/filtrar por acción/i).fill("accion-que-no-existe-jamas");
      await expect(page.getByText(/no hay entradas que cumplan el filtro/i)).toBeVisible({
        timeout: 15_000,
      });

      // Y al limpiarlo vuelve a haber entradas, lo que demuestra que el filtro era
      // lo que las ocultaba y no un fallo de carga.
      await page.getByLabel(/filtrar por acción/i).fill("");
      await expect(page.getByText(/entrada\(s\)/)).toHaveText(totalConTodo, { timeout: 15_000 });
    });

    test("los usuarios son de CONSULTA y la pantalla lo dice en lugar de ofrecer un formulario roto", async ({
      page,
    }) => {
      // ── La ausencia se declara, no se disimula ────────────────────────────
      //
      // La API solo publica `GET /v1/users`: no hay alta ni edición. La pantalla
      // anterior prometía `POST /v1/users` y `PUT /v1/users/{id}`, que NO existen.
      // Un formulario que devuelve `404` al guardar es peor que no tenerlo: el
      // usuario rellena ocho campos y descubre que su trabajo se perdió.
      await page.goto("/#/users");

      await expect(page.getByText(/esta pantalla es de consulta/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(/se hacen por api/i)).toBeVisible();

      // El administrador sembrado aparece **en la tabla**. Se pide la celda y no
      // el texto suelto: `e2e-admin` está además en la barra de sesión y en el
      // menú, así que `getByText` resuelve a tres elementos y Playwright falla por
      // ambigüedad — le pasó a este test.
      const tablaUsuarios = page.locator("table").first();
      await expect(tablaUsuarios.getByRole("cell", { name: "e2e-admin" })).toBeVisible({
        timeout: 15_000,
      });

      // Y **no hay ningún botón de guardar**, porque no hay nada que guardar.
      for (const prohibido of [/guardar/i, /crear usuario/i, /nuevo usuario/i, /restablecer/i]) {
        await expect(page.getByRole("button", { name: prohibido })).toHaveCount(0);
      }

      await expect(page.locator("body")).not.toContainText("NaN");
      await expect(page.locator("body")).not.toContainText("undefined");
    });

    test("un descuadre real sale como ALARMA, con la tabla y los valores del servidor", async ({
      page,
      request,
    }) => {
      // ── Se fabrica el descuadre por el camino del servidor ────────────────
      //
      // Se da de alta un producto por la API y se fuerza la proyección a 0. El
      // `product_stock` es un valor derivado que solo escriben las funciones del
      // motor, así que este es el estado que el reporte existe para detectar: la
      // proyección dice 0 y el ledger dice 1000.
      const acceso = await request.post(`${API_URL}/v1/auth/login`, {
        data: { username: USUARIO, password: PASSWORD },
      });
      expect(acceso.ok(), "el login por API debe funcionar para preparar el dato").toBeTruthy();
      const { accessToken } = (await acceso.json()) as { accessToken: string };

      const codigo = `E2E${Date.now().toString().slice(-6)}`;
      const alta = await request.post(`${API_URL}/v1/products`, {
        headers: { Authorization: `Bearer ${accessToken}` },
        data: { productCode: codigo, name: "Producto descuadrado E2E", unitMeasure: "PZA" },
      });
      expect(alta.status(), "el alta tiene que ser 201").toBe(201);

      // El descuadre se inyecta en la base con una consulta aparte, porque no hay
      // endpoint que escriba una proyección — y no debe haberlo.
      await inyectarDescuadre(codigo);

      await page.goto("/#/reports");

      await expect(page.getByText(/el inventario no cuadra/i)).toBeVisible({ timeout: 15_000 });
      await expect(page.locator("body")).not.toContainText("undefined");

      // La tabla de divergencias, con su fila.
      await expect(page.getByRole("columnheader", { name: /producto/i })).toBeVisible();
      await expect(page.getByRole("cell", { name: codigo })).toBeVisible();
    });
  });
});

/**
 * Fuerza un descuadre entre la proyección y el ledger, por SQL directo.
 *
 * No es un atajo: **no existe endpoint que escriba `product_stock` a propósito**,
 * porque es una proyección derivada y escribirla por API sería justo el fallo que
 * el reporte de reconciliación existe para detectar. Así que el descuadre se
 * fabrica donde se fabricaría de verdad: tocando la base.
 *
 * Se ejecuta con el CLI de Playwright `request` no sirve —no habla SQL— así que va
 * por el binario del contenedor mediante un proceso hijo.
 */
async function inyectarDescuadre(codigoProducto: string): Promise<void> {
  await ejecutarSql(`
    SELECT registrar_movimiento(
      (SELECT id FROM products WHERE product_code = '${codigoProducto}'),
      1, 'INITIAL', 1000, NULL,
      (SELECT id FROM locations WHERE kind = 'UNASSIGNED'),
      NULL,NULL,NULL,NULL,NULL,NULL,NULL);
    UPDATE product_stock SET quantity = 0
      WHERE product_id = (SELECT id FROM products WHERE product_code = '${codigoProducto}');
  `);
}

/**
 * Ejecuta SQL contra la base del entorno de pruebas.
 *
 * **Se usa SQL directo para PREPARAR escenarios, nunca para comprobar.** No hay
 * endpoint que cree bodegas ni que dé existencias iniciales —el stock entra por
 * recepción de factura, que es un flujo entero—, así que fabricar el escenario
 * mínimo es más honesto que encadenar cinco peticiones para probar una pantalla.
 *
 * Lo que se comprueba siempre se lee de la API o de la pantalla, jamás de aquí:
 * una prueba que se prepara y se verifica por el mismo camino no prueba el camino.
 */
async function ejecutarSql(sql: string): Promise<string> {
  const { execFileSync } = await import("node:child_process");
  const base = process.env.E2E_DB_NAME ?? "stock_e2e_web";
  const usuario = process.env.E2E_DB_USER ?? "stock_app";
  const contenedor = process.env.E2E_DB_CONTAINER ?? "stock-postgres";

  return execFileSync(
    "docker",
    ["exec", contenedor, "psql", "-U", usuario, "-d", base, "-tAq", "-c", sql],
    { encoding: "utf-8" },
  ).trim();
}

/** Inicia sesión como administrador y devuelve la cabecera de autorización. */
async function pedirToken(
  request: import("@playwright/test").APIRequestContext,
): Promise<{ Authorization: string }> {
  const acceso = await request.post(`${API_URL}/v1/auth/login`, {
    data: { username: USUARIO, password: PASSWORD },
  });
  const { accessToken } = (await acceso.json()) as { accessToken: string };
  return { Authorization: `Bearer ${accessToken}` };
}

/**
 * Deja un producto con existencias en la ubicación por defecto.
 *
 * Es el escenario mínimo para poder empacar: sin stock, el empaque devuelve `422
 * INSUFFICIENT_STOCK` —correctamente— y la prueba no llegaría a ver el descuento.
 * Se usa SQL y no un movimiento por API porque el stock inicial entra por
 * recepción de factura, que es un flujo completo que aquí no se está probando.
 */
async function prepararProductoConStock(code: string, name: string): Promise<void> {
  await ejecutarSql(`
    INSERT INTO products (product_code, name, unit_measure_id)
      VALUES ('${code}', '${name}', 1)
      ON CONFLICT (product_code) DO NOTHING;
    INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity)
      SELECT p.id, l.id, l.warehouse_id, 100
      FROM products p, locations l
      WHERE p.product_code = '${code}' AND l.kind = 'UNASSIGNED'
        AND NOT EXISTS (
          SELECT 1 FROM stock_by_location s WHERE s.product_id = p.id AND s.location_id = l.id
        );
    UPDATE product_stock SET quantity = 100
      WHERE product_id = (SELECT id FROM products WHERE product_code = '${code}');
  `);
}

/**
 * Prepara una bodega de destino y un producto con existencias, para traslados.
 *
 * **La bodega de destino necesita su ubicación de RECEPCIÓN.** Sin ella,
 * `POST /v1/transfers/{id}/receive` responde `422 NO_RECEIVING_LOCATION` —con
 * razón: la mercancía tiene que llegar a algún sitio— y el test fallaba por un
 * dato incompleto del escenario, no por el código.
 */
async function prepararTraslado(): Promise<void> {
  await ejecutarSql(`
    INSERT INTO warehouses (code, name, is_default) VALUES ('SUC', 'Sucursal', false)
      ON CONFLICT (code) DO NOTHING;
    INSERT INTO locations (warehouse_id, hall, code, "position", levels, kind)
      SELECT w.id, 'R', 'A', 'A', 1, 'RECEIVING' FROM warehouses w
      WHERE w.code = 'SUC'
        AND NOT EXISTS (
          SELECT 1 FROM locations l WHERE l.warehouse_id = w.id AND l.kind = 'RECEIVING'
        );
    INSERT INTO products (product_code, name, unit_measure_id)
      VALUES ('E2EW1', 'Traslado E2E', 1)
      ON CONFLICT (product_code) DO NOTHING;
    INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity)
      SELECT p.id, l.id, l.warehouse_id, 100
      FROM products p, locations l
      WHERE p.product_code = 'E2EW1' AND l.kind = 'UNASSIGNED'
        AND NOT EXISTS (
          SELECT 1 FROM stock_by_location s WHERE s.product_id = p.id AND s.location_id = l.id
        );
    UPDATE product_stock SET quantity = 100
      WHERE product_id = (SELECT id FROM products WHERE product_code = 'E2EW1');
  `);
}

/** El id de la bodega de destino, que la base asigna. */
async function idBodegaSucursal(): Promise<number> {
  return Number(await ejecutarSql("SELECT id FROM warehouses WHERE code = 'SUC'"));
}
