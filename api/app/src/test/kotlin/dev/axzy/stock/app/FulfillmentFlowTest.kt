package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Surtido (M04) y exportación al ERP (M09) por HTTP real.
 *
 * Los nombres S1…S9 son los de docs/flujos/surtido-y-erp.md §7. **S9** (una orden
 * exportada no se cancela) se comprueba en el dominio: el contrato de M04 no define
 * ninguna ruta de anulación.
 */
class FulfillmentFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun approver(): Long {
        val existing = db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).list()
        if (existing.isEmpty()) createUser("supervisor", USER_PASSWORD, jefa)
        return db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).single()
    }

    private fun product(token: String, code: String = "ABRAA1B") =
        post("/v1/products", mapOf("productCode" to code, "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

    private fun shelf(token: String, hall: String = "04"): Long {
        val response = post(
            "/v1/locations",
            mapOf("warehouseId" to 1, "hall" to hall, "code" to "B", "position" to "C"),
            token,
        )
        return response.at<Int>("id").toLong()
    }

    /** Deja existencias en un estante: es de donde sale el surtido. */
    private fun stockIn(token: String, hall: String, quantity: Int, who: Long) =
        post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "ABRAA1B", "locationCode" to "$hall-B-C", "quantity" to quantity,
                "direction" to "INCREASE", "reason" to "COUNT_ERROR", "origin" to "MANUAL_COUNT",
                "approverId" to who,
            ),
            token,
        )

    private fun order(token: String, quantity: Int = 10, number: String? = null): Long {
        val body = buildMap<String, Any?> {
            if (number != null) put("orderNumber", number)
            put("packagingDate", "2026-10-07")
            put("lines", listOf(mapOf("productCode" to "ABRAA1B", "quantity" to quantity)))
        }
        val response = post("/v1/orders", body, token)
        assertThat(response.status).describedAs("alta de orden: ${response.raw}").isEqualTo(201)
        return response.at<Int>("id").toLong()
    }

    private fun lineId(token: String, orderId: Long): Long =
        get("/v1/orders/$orderId", token).at<List<Map<String, Any?>>>("lines").single()["id"].let { (it as Number).toLong() }

    private fun packageLine(token: String, orderId: Long, line: Long, quantity: Int, locationId: Long) =
        post(
            "/v1/orders/$orderId/lines/$line/package",
            mapOf("quantity" to quantity, "locationId" to locationId),
            token,
        )

    private fun quantityAt(token: String, hall: String): String? {
        val items = get("/v1/inventory/stock?onlyWithStock=false&locationCode=$hall-B-C", token)
            .at<List<Map<String, Any?>>>("items")
        return items.firstOrNull()?.get("quantity") as String?
    }

    // ── Alta y consulta ─────────────────────────────────────────────────────

    @Test
    fun `el alta genera el numero y devuelve la orden con sus lineas`() {
        val token = manager()
        product(token)

        val id = order(token)
        val detail = get("/v1/orders/$id", token)

        assertThat(detail.at<String>("orderNumber")).startsWith("ORD-")
        assertThat(detail.at<String>("status")).isEqualTo("PENDING")
        assertThat(detail.at<Int>("totalLines")).isEqualTo(1)
        assertThat(detail.at<Int>("packagedLines")).isZero()
        // La creación NO toca el inventario.
        assertThat(quantityAt(token, "04")).isNull()
        val line = detail.at<List<Map<String, Any?>>>("lines").single()
        assertThat(line["quantity"]).isEqualTo("10.000")
        assertThat(line["suppliedQuantity"]).isEqualTo("0.000")
        assertThat(line["supplied"]).isEqualTo(false)
    }

    @Test
    fun `una orden sin lineas se rechaza y una que no existe responde ORDER_NOT_FOUND`() {
        val token = manager()
        product(token)

        val vacia = post("/v1/orders", mapOf("lines" to emptyList<Any>()), token)
        assertThat(vacia.status).isEqualTo(422)
        assertThat(vacia.code).isEqualTo("EMPTY_ORDER")
        assertThat(get("/v1/orders/999999", token).code).isEqualTo("ORDER_NOT_FOUND")
    }

    // ── S1: empacar descuenta exactamente lo empacado ───────────────────────

    @Test
    fun `S1 - empacar descuenta exactamente lo empacado`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)

        val response = packageLine(token, id, line, quantity = 7, locationId = location)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<List<Map<String, Any?>>>("lines").single()["suppliedQuantity"]).isEqualTo("7.000")
        // 100 menos 7.
        assertThat(quantityAt(token, "04")).isEqualTo("93.000")
        assertThat(response.at<String>("status")).isEqualTo("IN_PROGRESS")
    }

    @Test
    fun `empacar mas de lo pendiente se rechaza con cuanto queda`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 7, locationId = location)

        // Empacar 7 de 10 dos veces sumaría 14: se comprueba contra lo pendiente.
        val response = packageLine(token, id, line, quantity = 7, locationId = location)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("EXCEEDS_PENDING_QUANTITY")
        assertThat(quantityAt(token, "04")).isEqualTo("93.000")
    }

    @Test
    fun `empacar sin saldo se rechaza con lo que hay y sin mover nada`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 4, who)
        val id = order(token)
        val line = lineId(token, id)

        val response = packageLine(token, id, line, quantity = 10, locationId = location)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INSUFFICIENT_STOCK")
        assertThat(response.at<Map<String, String>>("details")["available"]).isEqualTo("4")
        assertThat(response.at<Map<String, String>>("details")["lineId"]).isEqualTo(line.toString())
        assertThat(quantityAt(token, "04")).isEqualTo("4.000")
    }

    @Test
    fun `una linea sin ubicacion no se puede empacar`() {
        val token = manager()
        product(token)
        val id = order(token)
        val line = lineId(token, id)

        val response = packageLine(token, id, line, quantity = 1, locationId = 0)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("LOCATION_REQUIRED")
    }

    // ── S2, S3 y S4: confirmar ──────────────────────────────────────────────

    @Test
    fun `S2 - confirmar descuenta solo lo que falta`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 7, locationId = location)

        val response = post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(200)
        // Baja 3 más, no 10: lo empacado ya había salido.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
        val confirmed = response.at<List<Map<String, Any?>>>("lines").single()
        assertThat(confirmed["suppliedQuantity"]).isEqualTo("10.000")
        assertThat(confirmed["supplied"]).isEqualTo(true)
        assertThat(response.at<String>("status")).isEqualTo("PENDING_TO_OUT")
    }

    @Test
    fun `S3 - confirmar dos veces no descuenta dos veces`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 4, locationId = location)

        val primera = post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)
        val segunda = post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)

        assertThat(primera.status).isEqualTo(200)
        assertThat(segunda.status).isEqualTo(409)
        assertThat(segunda.code).isEqualTo("ORDER_ALREADY_CONFIRMED")
        // Y el inventario bajó una sola vez: 100 − 10.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S4 - confirmar sin existencias no deja nada a medias`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 4, who)
        val id = order(token)
        val line = lineId(token, id)
        // La ubicación de la línea la anota el empaque: M04 no tiene ruta de asignación,
        // así que un empalme de 1 es lo que deja la línea con su estante.
        packageLine(token, id, line, quantity = 1, locationId = location)

        val response = post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INSUFFICIENT_STOCK")
        // Faltan 9 y quedan 3.
        assertThat(response.at<Map<String, String>>("details")["available"]).isEqualTo("3")
        assertThat(response.at<Map<String, String>>("details")["lineId"]).isEqualTo(line.toString())
        // La confirmación no se llevó nada: ni el estado ni el saldo.
        assertThat(get("/v1/orders/$id", token).at<String>("status")).isEqualTo("IN_PROGRESS")
        assertThat(quantityAt(token, "04")).isEqualTo("3.000")
        // El único movimiento de salida es el del empalme de 1, no el de la confirmación.
        assertThat(
            db.sql("SELECT COALESCE(sum(units), 0) FROM kardex WHERE move_type = 'FULFILLMENT_OUT'")
                .query(java.math.BigDecimal::class.java).single(),
        ).isEqualByComparingTo("1.000")
    }

    // ── S5, S6 y S7: la exportación al ERP ──────────────────────────────────

    @Test
    fun `S5 - sin ERP la exportacion falla con motivo y el inventario si salio`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 10, locationId = location)

        val response = post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)
        val detail = get("/v1/orders/$id", token)

        assertThat(response.status).isEqualTo(200)
        assertThat(detail.at<String>("status")).isEqualTo("PENDING_TO_OUT")
        assertThat(detail.at<Boolean>("converted")).isFalse()
        assertThat(detail.at<String>("exportStatus")).isEqualTo("FAILED")
        assertThat(detail.at<Int>("exportAttempts")).isEqualTo(1)
        assertThat(detail.at<String>("exportLastError")).startsWith("ERP_NOT_CONNECTED")
        // La confirmación NO se revierte: el stock ya salió.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S6 - el reintento manual vuelve a fallar con el mismo motivo y cuenta el intento`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 10, locationId = location)
        post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)

        val response = post("/v1/orders/$id/export", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<Int>("attempts")).isEqualTo(2)
        assertThat(response.at<String>("status")).isEqualTo("FAILED")
        assertThat(response.at<String>("lastError")).startsWith("ERP_NOT_CONNECTED")
        // El inventario no se movió otra vez.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S7 - no se exporta una orden sin confirmar`() {
        val token = manager()
        product(token)
        val id = order(token)

        val response = post("/v1/orders/$id/export", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("ORDER_NOT_CONVERTED")
    }

    // ── S8: el faltante sigue las reglas de ajuste ──────────────────────────

    @Test
    fun `S8 - un faltante sin motivo no descuenta nada`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 10, locationId = location)

        val response = post(
            "/v1/orders/$id/shortage",
            mapOf("lineId" to line, "quantity" to 3),
            token,
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S8 - un faltante con motivo descuenta por la regla de ajuste`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        // Un empalme de 10 deja la ubicación anotada en la línea.
        packageLine(token, id, line, quantity = 10, locationId = location)

        val response = post(
            "/v1/orders/$id/shortage",
            mapOf("lineId" to line, "quantity" to 3, "reason" to "SHORTAGE", "note" to "Caja aplastada"),
            token,
        )

        assertThat(response.status).isEqualTo(200)
        // 100 − 10 empacados − 3 del faltante.
        assertThat(quantityAt(token, "04")).isEqualTo("87.000")
        // El movimiento lleva el origen del surtido, no un ajuste manual.
        assertThat(
            db.sql("SELECT adjustment_origin FROM kardex WHERE move_type = 'ADJUSTMENT_OUT'")
                .query(String::class.java).single(),
        ).isEqualTo("FULFILLMENT_SHORTAGE")
    }

    @Test
    fun `S8 - sin permiso de ajuste el faltante se rechaza`() {
        val jefe = manager()
        val who = approver()
        product(jefe)
        val location = shelf(jefe)
        stockIn(jefe, "04", 100, who)
        val orderId = order(jefe)
        val line = lineId(jefe, orderId)
        packageLine(jefe, orderId, line, quantity = 10, locationId = location)

        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)
        // El operador de almacén tiene `order.package` pero no `inventory.adjust_shortage`.
        db.sql(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            SELECT r.id, p.id, 'ALL' FROM roles r, permissions p
            WHERE r.key = :role AND p.key IN ('order.view', 'order.package')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("role", BaseRole.WAREHOUSE_OPERATOR.key.value).update()

        val response = post(
            "/v1/orders/$orderId/shortage",
            mapOf("lineId" to line, "quantity" to 1, "reason" to "SHORTAGE"),
            token,
        )

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("PERMISSION_DENIED")
    }

    // ── Permisos por ruta ───────────────────────────────────────────────────

    @Test
    fun `un operador de almacen no confirma ni exporta ordenes`() {
        createUser("operador2", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador2", USER_PASSWORD)

        assertThat(post("/v1/orders/1/confirm", emptyMap<String, Any>(), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/orders/1/export", emptyMap<String, Any>(), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/orders", mapOf("lines" to emptyList<Any>()), token).code).isEqualTo("PERMISSION_DENIED")
    }

    // ── Asignación de la línea (M04) ────────────────────────────────────────

    @Test
    fun `asignar una linea la deja a cargo de alguien y con su estanteria`() {
        val token = manager()
        product(token)
        val estante = shelf(token)
        val pedido = order(token)
        val linea = lineId(token, pedido)
        val operador = createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)

        val response = put(
            "/v1/orders/$pedido/lines/$linea/assign",
            mapOf("userId" to operador, "locationId" to estante),
            token,
        )

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("ASSIGNED")
        assertThat((response.at<Number>("assignedUserId")).toLong()).isEqualTo(operador)
        assertThat((response.at<Number>("locationId")).toLong()).isEqualTo(estante)
        // Y el detalle lo publica, que es lo que el operador consulta para saber qué le toca.
        val detalle = get("/v1/orders/$pedido", token).at<List<Map<String, Any?>>>("lines").single()
        // El nombre visible de la persona, no su usuario: es lo que se lee en pantalla.
        assertThat(detalle["assignedUserName"]).isEqualTo("Usuario operador")
        assertThat(detalle["locationDisplayName"]).isEqualTo("04-B-C")
    }

    @Test
    fun `sin userId la asignacion es a quien llama`() {
        val token = manager()
        product(token)
        val pedido = order(token)
        val linea = lineId(token, pedido)
        val jefa = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()

        val response = put("/v1/orders/$pedido/lines/$linea/assign", mapOf<String, Any?>(), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat((response.at<Number>("assignedUserId")).toLong()).isEqualTo(jefa)
    }

    @Test
    fun `no se asigna una linea ya surtida`() {
        val token = manager()
        val quien = approver() as Long
        product(token)
        val estante = shelf(token)
        stockIn(token, "04", 10, quien)
        val pedido = order(token)
        val linea = lineId(token, pedido)
        packageLine(token, pedido, linea, 10, estante)

        val response = put("/v1/orders/$pedido/lines/$linea/assign", mapOf<String, Any?>(), token)

        // Asignar trabajo sobre mercancía que ya salió sería decir que alguien la va a sacar
        // dos veces.
        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
    }

    @Test
    fun `asignar exige el permiso y un usuario y una ubicacion que existan`() {
        val token = manager()
        product(token)
        val pedido = order(token)
        val linea = lineId(token, pedido)
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val suToken = accessTokenOf("operador", USER_PASSWORD)

        val sinPermiso = put("/v1/orders/$pedido/lines/$linea/assign", mapOf<String, Any?>(), suToken)
        val usuarioInventado = put("/v1/orders/$pedido/lines/$linea/assign", mapOf("userId" to 999999), token)
        val ubicacionInventada = put("/v1/orders/$pedido/lines/$linea/assign", mapOf("locationId" to 999999), token)

        assertThat(sinPermiso.code).isEqualTo("PERMISSION_DENIED")
        assertThat(usuarioInventado.status).isEqualTo(422)
        assertThat(usuarioInventado.code).isEqualTo("ASSIGNEE_NOT_FOUND")
        assertThat(ubicacionInventada.code).isEqualTo("LOCATION_NOT_FOUND")
    }
}
