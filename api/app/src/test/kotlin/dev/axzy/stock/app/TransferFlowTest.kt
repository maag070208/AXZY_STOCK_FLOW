package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Traslados (M05) por HTTP real.
 *
 * Los nombres T1…T8 son los de docs/flujos/traslados.md §7. T1, T2, T5 y T6 también
 * están probadas **contra el motor** (`TransferEngineTest`), que es donde se demuestra
 * que la mercancía en tránsito sigue contando; aquí se comprueba lo mismo por las
 * rutas, con sus permisos y su contrato.
 */
class TransferFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private var destinationWarehouse = 0L
    private var destination = 0L
    private var origin = 0L

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun approver(): Long {
        val existing = db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).list()
        if (existing.isEmpty()) createUser("supervisor", USER_PASSWORD, jefa)
        return db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).single()
    }

    @BeforeEach
    fun prepareSucursal() {
        // La bodega de destino necesita su ubicación de recepción, que siembra el
        // esquema para la principal pero no para una bodega nueva.
        destinationWarehouse = db.sql(
            "INSERT INTO warehouses (code, name, is_default, active) VALUES ('BRANCH', 'Sucursal', FALSE, TRUE) RETURNING id",
        ).query(Long::class.java).single()
        destination = db.sql(
            """
            INSERT INTO locations (warehouse_id, hall, code, position, levels, kind)
            VALUES (:warehouse, 'RECEIVING', 'RECEIVING', 'RECEIVING', 1, 'RECEIVING') RETURNING id
            """.trimIndent(),
        ).param("warehouse", destinationWarehouse).query(Long::class.java).single()
        origin = db.sql("SELECT id FROM locations WHERE kind = 'RECEIVING' AND warehouse_id = 1")
            .query(Long::class.java).single()
    }

    private fun product(token: String, code: String = "ABRAA1B") =
        post("/v1/products", mapOf("productCode" to code, "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

    private fun stockIn(token: String, quantity: Int, who: Long) =
        post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "ABRAA1B", "locationCode" to "RECEIVING", "quantity" to quantity,
                "direction" to "INCREASE", "reason" to "COUNT_ERROR", "origin" to "MANUAL_COUNT",
                "approverId" to who,
            ),
            token,
        )

    private fun transfer(token: String, quantity: Int = 40, from: Long = 1, to: Long? = null): Long {
        val response = post(
            "/v1/transfers",
            mapOf(
                "fromWarehouseId" to from,
                "toWarehouseId" to (to ?: destinationWarehouse),
                "lines" to listOf(mapOf("productCode" to "ABRAA1B", "quantity" to quantity)),
            ),
            token,
        )
        assertThat(response.status).describedAs("alta de traslado: ${response.raw}").isEqualTo(201)
        val header = response.at<Map<String, Any?>>("transfer")
        return (header["id"] as Number).toLong()
    }

    /** Con los tres decimales del contrato: `COALESCE(x, 0)` devuelve la escala del literal. */
    private fun three(value: java.math.BigDecimal): String = value.setScale(3).toPlainString()

    private fun quantityAt(locationId: Long): String =
        three(
            db.sql("SELECT COALESCE(quantity, 0) FROM stock_by_location WHERE location_id = :location")
                .param("location", locationId).query(java.math.BigDecimal::class.java).optional()
                .orElse(java.math.BigDecimal.ZERO),
        )

    private fun inTransit(): String =
        three(
            db.sql("SELECT COALESCE(sum(quantity), 0) FROM stock_in_transit WHERE received_at IS NULL")
                .query(java.math.BigDecimal::class.java).single(),
        )

    private fun totalStock(): String =
        three(
            db.sql("SELECT COALESCE(quantity, 0) FROM product_stock").query(java.math.BigDecimal::class.java)
                .optional().orElse(java.math.BigDecimal.ZERO),
        )

    // ── T1 y T2: despachar y recibir ────────────────────────────────────────

    @Test
    fun `T1 - despachar saca del origen y deja en transito sin cambiar el total`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)

        val response = post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<Map<String, Any?>>("transfer")["status"]).isEqualTo("DISPATCHED")
        assertThat(quantityAt(origin)).isEqualTo("60.000")
        assertThat(inTransit()).isEqualTo("40.000")
        // La mercancía en el camión SIGUE contando.
        assertThat(totalStock()).isEqualTo("100.000")
    }

    @Test
    fun `T2 - recibir pone en el destino y cierra el transito`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)
        post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)

        val response = post("/v1/transfers/$id/receive", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<Map<String, Any?>>("transfer")["status"]).isEqualTo("RECEIVED")
        assertThat(quantityAt(destination)).isEqualTo("40.000")
        assertThat(quantityAt(origin)).isEqualTo("60.000")
        assertThat(inTransit()).isEqualTo("0.000")
        assertThat(totalStock()).isEqualTo("100.000")
    }

    @Test
    fun `T3 - recibir dos veces no mueve la mercancia dos veces`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)
        post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)
        post("/v1/transfers/$id/receive", emptyMap<String, Any>(), token)

        val segunda = post("/v1/transfers/$id/receive", emptyMap<String, Any>(), token)

        // 409 y no 422: `INVALID_TRANSITION` es un conflicto de estado, y el catálogo
        // de errores lo fija ahí (docs/api/errores.md).
        assertThat(segunda.status).isEqualTo(409)
        assertThat(segunda.code).isEqualTo("INVALID_TRANSITION")
        assertThat(quantityAt(destination)).isEqualTo("40.000")
        assertThat(totalStock()).isEqualTo("100.000")
    }

    @Test
    fun `T4 - el origen y el destino de la misma bodega no son un traslado`() {
        val token = manager()
        product(token)

        val response = post(
            "/v1/transfers",
            mapOf(
                "fromWarehouseId" to 1,
                "toWarehouseId" to 1,
                "lines" to listOf(mapOf("productCode" to "ABRAA1B", "quantity" to 10)),
            ),
            token,
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("SAME_WAREHOUSE")
    }

    @Test
    fun `T5 - sin existencias no se despacha`() {
        val token = manager()
        product(token)
        stockIn(token, 10, approver())
        val id = transfer(token, quantity = 40)

        val response = post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INSUFFICIENT_STOCK")
        assertThat(response.at<Map<String, String>>("details")["available"]).isEqualTo("10")
        // Nada a medias: ni salida, ni tránsito, ni cambio de estado.
        assertThat(quantityAt(origin)).isEqualTo("10.000")
        assertThat(inTransit()).isEqualTo("0.000")
        assertThat(get("/v1/transfers/$id", token).at<Map<String, Any?>>("transfer")["status"]).isEqualTo("DRAFT")
    }

    // ── T6 y T7: cancelar ───────────────────────────────────────────────────

    @Test
    fun `T6 - cancelar un despachado devuelve la mercancia a su origen`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)
        post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)

        val response = post("/v1/transfers/$id/cancel", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<Map<String, Any?>>("transfer")["status"]).isEqualTo("CANCELLED")
        assertThat(quantityAt(origin)).isEqualTo("100.000")
        assertThat(inTransit()).isEqualTo("0.000")
        assertThat(totalStock()).isEqualTo("100.000")
    }

    @Test
    fun `T7 - un traslado recibido no se cancela`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)
        post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)
        post("/v1/transfers/$id/receive", emptyMap<String, Any>(), token)

        val response = post("/v1/transfers/$id/cancel", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
        // Lo recibido está en el destino y ahí se queda.
        assertThat(quantityAt(destination)).isEqualTo("40.000")
        assertThat(get("/v1/transfers/$id", token).at<Map<String, Any?>>("transfer")["status"]).isEqualTo("RECEIVED")
    }

    // ── T8: el tránsito tiene su propio permiso ─────────────────────────────

    @Test
    fun `T8 - el transito se ve con su propio permiso y no con el de traslados`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)
        post("/v1/transfers/$id/dispatch", emptyMap<String, Any>(), token)

        // Un usuario que solo vigila la deuda: `transit.view` y nada de traslados.
        createUser("vigilante", USER_PASSWORD, BaseRole.AUDITOR.key.value)
        db.sql("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE key = :role)")
            .param("role", BaseRole.AUDITOR.key.value).update()
        db.sql(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            SELECT r.id, p.id, 'ALL' FROM roles r, permissions p
            WHERE r.key = :role AND p.key = 'transit.view'
            """.trimIndent(),
        ).param("role", BaseRole.AUDITOR.key.value).update()
        val vigilante = accessTokenOf("vigilante", USER_PASSWORD)

        val reporte = get("/v1/transfers/in-transit", vigilante)
        val listado = get("/v1/transfers", vigilante)

        assertThat(reporte.status).isEqualTo(200)
        // El informe devuelve un ARREGLO, sin sobre de paginación: se comprueba su
        // contenido tal cual, porque el contrato fija esa forma.
        assertThat(reporte.raw).startsWith("[")
        assertThat(reporte.raw).contains("\"code\":\"TR-2026-0001\"")
        assertThat(reporte.raw).contains("\"fromWarehouseCode\":\"MAIN\"")
        assertThat(reporte.raw).contains("\"toWarehouseCode\":\"BRANCH\"")
        // Los días los cuenta el servidor, no la pantalla.
        assertThat(reporte.raw).contains("\"daysInTransit\":0")
        assertThat(reporte.raw).contains("\"dispatchedByName\":\"Usuario jefa\"")
        assertThat(listado.status).isEqualTo(403)
    }

    // ── El contrato de las rutas ────────────────────────────────────────────

    @Test
    fun `el alta sin lineas y un traslado que no existe se rechazan`() {
        val token = manager()
        product(token)

        val vacio = post("/v1/transfers", mapOf("fromWarehouseId" to 1, "toWarehouseId" to destinationWarehouse, "lines" to emptyList<Any>()), token)
        assertThat(vacio.status).isEqualTo(422)
        assertThat(vacio.code).isEqualTo("EMPTY_TRANSFER")

        assertThat(get("/v1/transfers/999999", token).code).isEqualTo("TRANSFER_NOT_FOUND")
    }

    @Test
    fun `el listado trae los contadores y el detalle las lineas`() {
        val token = manager()
        product(token)
        stockIn(token, 100, approver())
        val id = transfer(token, quantity = 40)

        val listado = get("/v1/transfers?status=DRAFT", token)
        val detalle = get("/v1/transfers/$id", token)

        assertThat(listado.at<Int>("totalItems")).isEqualTo(1)
        assertThat(listado.at<List<Map<String, Any?>>>("items").single()["totalUnits"]).isEqualTo("40.000")
        assertThat(listado.at<List<Map<String, Any?>>>("items").single()["lineCount"]).isEqualTo(1)
        assertThat(detalle.at<Map<String, Any?>>("transfer")["code"]).isEqualTo("TR-2026-0001")
        assertThat(detalle.at<List<Map<String, Any?>>>("lines").single()["quantity"]).isEqualTo("40.000")
        // La línea no dice de qué ubicación sale: el motor usa la recepción del origen.
        assertThat(detalle.at<List<Map<String, Any?>>>("lines").single()["fromLocationDisplayName"]).isNull()
    }

    @Test
    fun `un operador de piso no cancela traslados y el permiso se comprueba antes del 404`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        // Cancelar es sensible: aunque el traslado no exista, el permiso decide primero.
        val cancelar = post("/v1/transfers/999999/cancel", emptyMap<String, Any>(), token)

        assertThat(cancelar.status).isEqualTo(403)
        assertThat(cancelar.code).isEqualTo("PERMISSION_DENIED")
    }
}
