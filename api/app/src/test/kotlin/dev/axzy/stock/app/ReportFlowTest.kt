package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Los reportes (docs/api/reportes.md) por HTTP real.
 *
 * Lo que se comprueba aquí y no en las pruebas de escritura: que los **totales se
 * calculan sobre el filtro entero** y no sobre la página, que el resumen no cuenta
 * los traslados internos como entradas ni salidas, y que el veredicto de la
 * reconciliación lo da el servidor.
 */
class ReportFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun approver(): Long = createUser("supervisor", USER_PASSWORD, jefa)

    private fun createProduct(token: String, code: String, name: String = "PRODUCTO $code") =
        post("/v1/products", mapOf("productCode" to code, "name" to name, "unitMeasure" to "PZA"), token)

    private fun createShelf(token: String, hall: String) =
        post("/v1/locations", mapOf("warehouseId" to 1, "hall" to hall, "code" to "B", "position" to "C"), token)

    private fun stockIn(token: String, code: String, location: String, quantity: Int, who: Long) =
        post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to code,
                "locationCode" to location,
                "quantity" to quantity,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
                "approverId" to who,
            ),
            token,
        )

    private fun today(): String = LocalDate.now().toString()

    // ── Existencias ─────────────────────────────────────────────────────────

    @Test
    fun `el reporte de existencias trae los totales del filtro entero y no de la pagina`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        createProduct(token, "AAA002")
        createShelf(token, "04")
        createShelf(token, "05")
        stockIn(token, "AAA001", "04-B-C", 100, who)
        stockIn(token, "AAA001", "05-B-C", 50, who)
        stockIn(token, "AAA002", "04-B-C", 25, who)

        // Una fila por página, y los totales siguen siendo los de las tres.
        val response = get("/v1/reports/inventory?size=1&onlyWithStock=false", token)

        assertThat(response.at<List<Any>>("items")).hasSize(1)
        assertThat(response.at<Int>("totalItems")).isEqualTo(3)
        assertThat(response.at<Map<String, Any?>>("totals")["distinctProducts"]).isEqualTo(2)
        assertThat(response.at<Map<String, Any?>>("totals")["totalUnits"]).isEqualTo("175.000")
    }

    @Test
    fun `un producto en tres estantes es un producto distinto, no tres`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        createShelf(token, "04")
        createShelf(token, "05")
        createShelf(token, "06")
        stockIn(token, "AAA001", "04-B-C", 10, who)
        stockIn(token, "AAA001", "05-B-C", 10, who)
        stockIn(token, "AAA001", "06-B-C", 10, who)

        val totals = get("/v1/reports/inventory", token).at<Map<String, Any?>>("totals")

        assertThat(totals["distinctProducts"]).isEqualTo(1)
        assertThat(totals["totalUnits"]).isEqualTo("30.000")
    }

    @Test
    fun `el reporte filtra por bodega`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        stockIn(token, "AAA001", "RECEPCION", 10, who)

        val propia = get("/v1/reports/inventory?warehouseCode=PRINCIPAL", token)
        val ajena = get("/v1/reports/inventory?warehouseCode=NORTE", token)

        assertThat(propia.at<Int>("totalItems")).isEqualTo(1)
        assertThat(ajena.at<Int>("totalItems")).isZero()
    }

    // ── Resumen de movimientos ──────────────────────────────────────────────

    @Test
    fun `el resumen agrupa por dia y no cuenta ubicar como entrada ni como salida`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        createShelf(token, "04")
        stockIn(token, "AAA001", "RECEPCION", 1000, who)
        post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "AAA001", "fromLocationCode" to "RECEPCION",
                "toLocationCode" to "04-B-C", "quantity" to 300,
            ),
            token,
        )
        post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "AAA001", "locationCode" to "04-B-C", "quantity" to 10,
                "direction" to "DECREASE", "reason" to "COUNT_ERROR", "origin" to "MANUAL_COUNT",
            ),
            token,
        )

        val response = get("/v1/reports/movements-summary?from=${today()}&to=${today()}&groupBy=day", token)
        val item = response.at<List<Map<String, Any?>>>("items").single()
        val totals = response.at<Map<String, Any?>>("totals")

        // Entra el aumento (1000) y sale la merma (10). El ubicar de 300 no suma: es
        // un traslado y el total del producto no cambió.
        assertThat(item["inUnits"]).isEqualTo("1000.000")
        assertThat(item["outUnits"]).isEqualTo("10.000")
        assertThat(item["netUnits"]).isEqualTo("990.000")
        // Pero sí cuenta como movimiento: pasó.
        assertThat(item["movements"]).isEqualTo(3)
        assertThat(totals["movements"]).isEqualTo(3)
        assertThat(totals["inUnits"]).isEqualTo("1000.000")
    }

    @Test
    fun `el resumen agrupa por producto y por ubicacion`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        createProduct(token, "AAA002")
        stockIn(token, "AAA001", "RECEPCION", 100, who)
        stockIn(token, "AAA002", "RECEPCION", 40, who)

        val byProduct = get("/v1/reports/movements-summary?from=${today()}&to=${today()}&groupBy=product", token)
            .at<List<Map<String, Any?>>>("items")
        val byLocation = get("/v1/reports/movements-summary?from=${today()}&to=${today()}&groupBy=location", token)
            .at<List<Map<String, Any?>>>("items")

        assertThat(byProduct.map { it["key"] }).containsExactly("AAA001", "AAA002")
        assertThat(byProduct.map { it["inUnits"] }).containsExactly("100.000", "40.000")
        assertThat(byLocation.single()["key"]).isEqualTo("RECEPCION")
        assertThat(byLocation.single()["inUnits"]).isEqualTo("140.000")
    }

    @Test
    fun `el resumen exige las dos fechas y un groupBy conocido`() {
        val token = manager()

        assertThat(get("/v1/reports/movements-summary?to=${today()}", token).code).isEqualTo("FIELD_REQUIRED")
        assertThat(get("/v1/reports/movements-summary?from=${today()}", token).code).isEqualTo("FIELD_REQUIRED")

        val bad = get("/v1/reports/movements-summary?from=${today()}&to=${today()}&groupBy=trimestre", token)
        assertThat(bad.status).isEqualTo(400)
        assertThat(bad.code).isEqualTo("INVALID_PARAMETER")
        assertThat(bad.at<Map<String, String>>("details")["allowed"]).contains("DAY")

        val inverted = get("/v1/reports/movements-summary?from=2026-10-31&to=2026-10-01", token)
        assertThat(inverted.code).isEqualTo("INVALID_FILTER_RANGE")
    }

    // ── Reconciliación ──────────────────────────────────────────────────────

    @Test
    fun `la reconciliacion dice sano cuando las proyecciones cuadran`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        stockIn(token, "AAA001", "RECEPCION", 100, who)

        val response = get("/v1/reports/kardex-vs-projection", token)

        assertThat(response.at<Boolean>("balanced")).isTrue()
        assertThat(response.at<Int>("divergences")).isZero()
        assertThat(response.at<List<Any>>("items")).isEmpty()
        assertThat(response.at<String>("checkedAt")).isNotBlank()
    }

    @Test
    fun `la reconciliacion encuentra el descuadre y lo dice con su signo`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        stockIn(token, "AAA001", "RECEPCION", 100, who)

        // Se rompe la proyección a mano: es lo que pasaría si alguien escribiera el
        // saldo por fuera del motor. El ledger sigue diciendo 100.
        db.sql("UPDATE product_stock SET quantity = quantity + 5").update()

        val response = get("/v1/reports/kardex-vs-projection", token)
        val item = response.at<List<Map<String, Any?>>>("items").single()

        assertThat(response.at<Boolean>("balanced")).isFalse()
        assertThat(response.at<Int>("divergences")).isEqualTo(1)
        assertThat(item["productCode"]).isEqualTo("AAA001")
        assertThat(item["projectedTotal"]).isEqualTo("105.000")
        assertThat(item["ledgerTotal"]).isEqualTo("100.000")
        assertThat(item["difference"]).isEqualTo("5.000")
    }

    // ── Exportación ─────────────────────────────────────────────────────────

    @Test
    fun `la exportacion a CSV trae las mismas filas que el listado y con sus filtros`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        createProduct(token, "BBB002")
        stockIn(token, "AAA001", "RECEPCION", 10, who)
        stockIn(token, "BBB002", "RECEPCION", 20, who)

        val todos = get("/v1/reports/kardex/export?format=csv", token)
        val filtrado = get("/v1/reports/kardex/export?format=csv&productCode=AAA001", token)

        assertThat(todos.status).isEqualTo(200)
        assertThat(todos.header("Content-Type")).contains("text/csv")
        assertThat(todos.header("Content-Disposition")).contains("attachment").contains("kardex.csv")
        // Cabecera + dos movimientos.
        assertThat(todos.raw.trim().lines()).hasSize(3)
        assertThat(todos.raw).contains("AAA001").contains("BBB002")

        // El archivo lleva el mismo filtro que el listado: si no, habría que
        // reconciliar dos vistas de los mismos datos.
        assertThat(filtrado.raw).contains("AAA001").doesNotContain("BBB002")
    }

    @Test
    fun `la exportacion a XLSX es un archivo de hoja de calculo`() {
        val token = manager()
        val who = approver()
        createProduct(token, "AAA001")
        stockIn(token, "AAA001", "RECEPCION", 10, who)

        val response = get("/v1/reports/kardex/export?format=xlsx", token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.header("Content-Type")).contains("spreadsheetml.sheet")
        assertThat(response.header("Content-Disposition")).contains("kardex.xlsx")
        // Un XLSX es un contenedor ZIP: sus dos primeros bytes lo dicen. Que las
        // cantidades sean celdas numéricas se comprueba en `KardexExportWriterTest`.
        assertThat(response.raw).startsWith("PK")
    }

    @Test
    fun `la exportacion exige un formato admitido`() {
        val token = manager()

        val sinFormato = get("/v1/reports/kardex/export", token)
        val desconocido = get("/v1/reports/kardex/export?format=pdf", token)

        assertThat(sinFormato.status).isEqualTo(400)
        assertThat(sinFormato.code).isEqualTo("INVALID_PARAMETER")
        assertThat(sinFormato.at<Map<String, String>>("details")["allowed"]).contains("csv").contains("xlsx")
        assertThat(desconocido.code).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `los reportes exigen sus permisos y no los del inventario`() {
        // `inventory.view` no basta para un reporte: la pantalla y su guard comparten
        // permiso, y dar otro haría que el usuario entrara y recibiera 403 al cargar.
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        assertThat(get("/v1/reports/inventory", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/reports/kardex-vs-projection", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/reports/kardex/export?format=csv", token).code).isEqualTo("PERMISSION_DENIED")
    }
}
