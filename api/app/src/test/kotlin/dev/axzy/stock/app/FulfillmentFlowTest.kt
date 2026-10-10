package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Fulfillment (M04) and export to the ERP (M09) over real HTTP.
 *
 * The names S1…S9 are those of docs/flujos/surtido-y-erp.md §7. **S9** (an exported order
 * is not cancelled) is checked in the domain: the M04 contract does not define
 * any cancellation route.
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

    /** Leaves stock on a shelf: it is where fulfillment draws from. */
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

    // ── Creation and query ─────────────────────────────────────────────────

    @Test
    fun `creating an order generates the number and returns the order with its lines`() {
        val token = manager()
        product(token)

        val id = order(token)
        val detail = get("/v1/orders/$id", token)

        assertThat(detail.at<String>("orderNumber")).startsWith("ORD-")
        assertThat(detail.at<String>("status")).isEqualTo("PENDING")
        assertThat(detail.at<Int>("totalLines")).isEqualTo(1)
        assertThat(detail.at<Int>("packagedLines")).isZero()
        // Creation does NOT touch the inventory.
        assertThat(quantityAt(token, "04")).isNull()
        val line = detail.at<List<Map<String, Any?>>>("lines").single()
        assertThat(line["quantity"]).isEqualTo("10.000")
        assertThat(line["suppliedQuantity"]).isEqualTo("0.000")
        assertThat(line["supplied"]).isEqualTo(false)
    }

    @Test
    fun `an order without lines is rejected, and one that does not exist answers ORDER_NOT_FOUND`() {
        val token = manager()
        product(token)

        val vacia = post("/v1/orders", mapOf("lines" to emptyList<Any>()), token)
        assertThat(vacia.status).isEqualTo(422)
        assertThat(vacia.code).isEqualTo("EMPTY_ORDER")
        assertThat(get("/v1/orders/999999", token).code).isEqualTo("ORDER_NOT_FOUND")
    }

    // ── S1: packing deducts exactly what was packed ──────────────────────────

    @Test
    fun `S1 - packing deducts exactly what was packed`() {
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
    fun `packing more than what is pending is rejected with what remains`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        packageLine(token, id, line, quantity = 7, locationId = location)

        // Packing 7 of 10 twice would add up to 14: it is checked against what is pending.
        val response = packageLine(token, id, line, quantity = 7, locationId = location)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("EXCEEDS_PENDING_QUANTITY")
        assertThat(quantityAt(token, "04")).isEqualTo("93.000")
    }

    @Test
    fun `packing without balance is rejected with what there is and moves nothing`() {
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
    fun `a line without a location cannot be packed`() {
        val token = manager()
        product(token)
        val id = order(token)
        val line = lineId(token, id)

        val response = packageLine(token, id, line, quantity = 1, locationId = 0)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("LOCATION_REQUIRED")
    }

    // ── S2, S3 and S4: confirming ─────────────────────────────────────────────

    @Test
    fun `S2 - confirming deducts only what is missing`() {
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
        // It deducts 3 more, not 10: what was packed had already left.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
        val confirmed = response.at<List<Map<String, Any?>>>("lines").single()
        assertThat(confirmed["suppliedQuantity"]).isEqualTo("10.000")
        assertThat(confirmed["supplied"]).isEqualTo(true)
        assertThat(response.at<String>("status")).isEqualTo("PENDING_TO_OUT")
    }

    @Test
    fun `S3 - confirming twice does not deduct twice`() {
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
        // And the inventory went down only once: 100 − 10.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S4 - confirming without stock leaves nothing half done`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 4, who)
        val id = order(token)
        val line = lineId(token, id)
        // The line's location is noted by the packing: M04 has no assignment route,
        // so locating 1 unit is what leaves the line with its shelf.
        packageLine(token, id, line, quantity = 1, locationId = location)

        val response = post("/v1/orders/$id/confirm", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INSUFFICIENT_STOCK")
        // 9 are missing and 3 are left.
        assertThat(response.at<Map<String, String>>("details")["available"]).isEqualTo("3")
        assertThat(response.at<Map<String, String>>("details")["lineId"]).isEqualTo(line.toString())
        // The confirmation did not take anything: neither the status nor the balance.
        assertThat(get("/v1/orders/$id", token).at<String>("status")).isEqualTo("IN_PROGRESS")
        assertThat(quantityAt(token, "04")).isEqualTo("3.000")
        // The only outflow movement is the one from the locate of 1, not the one from the confirmation.
        assertThat(
            db.sql("SELECT COALESCE(sum(units), 0) FROM kardex WHERE move_type = 'FULFILLMENT_OUT'")
                .query(java.math.BigDecimal::class.java).single(),
        ).isEqualByComparingTo("1.000")
    }

    // ── S5, S6 and S7: the ERP export ─────────────────────────────────────

    @Test
    fun `S5 - without an ERP the export fails with a reason, and the inventory did leave`() {
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
        // The confirmation is NOT rolled back: the stock has already left.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S6 - the manual retry fails again with the same reason and counts the attempt`() {
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
        // The inventory did not move again.
        assertThat(quantityAt(token, "04")).isEqualTo("90.000")
    }

    @Test
    fun `S7 - an unconfirmed order is not exported`() {
        val token = manager()
        product(token)
        val id = order(token)

        val response = post("/v1/orders/$id/export", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("ORDER_NOT_CONVERTED")
    }

    // ── S8: the shortage follows the adjustment rules ──────────────────────

    @Test
    fun `S8 - a shortage without a reason deducts nothing`() {
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
    fun `S8 - a shortage with a reason deducts through the adjustment rule`() {
        val token = manager()
        val who = approver()
        product(token)
        val location = shelf(token)
        stockIn(token, "04", 100, who)
        val id = order(token)
        val line = lineId(token, id)
        // Locating 10 leaves the location noted on the line.
        packageLine(token, id, line, quantity = 10, locationId = location)

        val response = post(
            "/v1/orders/$id/shortage",
            mapOf("lineId" to line, "quantity" to 3, "reason" to "SHORTAGE", "note" to "Caja aplastada"),
            token,
        )

        assertThat(response.status).isEqualTo(200)
        // 100 − 10 packed − 3 of the shortage.
        assertThat(quantityAt(token, "04")).isEqualTo("87.000")
        // The movement carries the fulfillment origin, not a manual adjustment.
        assertThat(
            db.sql("SELECT adjustment_origin FROM kardex WHERE move_type = 'ADJUSTMENT_OUT'")
                .query(String::class.java).single(),
        ).isEqualTo("FULFILLMENT_SHORTAGE")
    }

    @Test
    fun `S8 - without the adjustment permission the shortage is rejected`() {
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
        // The warehouse operator has `order.package` but not `inventory.adjust_shortage`.
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

    // ── Permissions per route ─────────────────────────────────────────────────

    @Test
    fun `a warehouse operator does not confirm or export orders`() {
        createUser("operador2", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador2", USER_PASSWORD)

        assertThat(post("/v1/orders/1/confirm", emptyMap<String, Any>(), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/orders/1/export", emptyMap<String, Any>(), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/orders", mapOf("lines" to emptyList<Any>()), token).code).isEqualTo("PERMISSION_DENIED")
    }

    // ── Line assignment (M04) ──────────────────────────────────────────────────

    @Test
    fun `assigning a line puts it in someone's charge and on their shelf`() {
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
        // And the detail publishes it, which is what the operator checks to know what is theirs.
        val detalle = get("/v1/orders/$pedido", token).at<List<Map<String, Any?>>>("lines").single()
        // The person's visible name, not their username: it is what is read on screen.
        assertThat(detalle["assignedUserName"]).isEqualTo("Usuario operador")
        assertThat(detalle["locationDisplayName"]).isEqualTo("04-B-C")
    }

    @Test
    fun `without userId the assignment is to the caller`() {
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
    fun `a line already packed is not assigned`() {
        val token = manager()
        val quien = approver() as Long
        product(token)
        val estante = shelf(token)
        stockIn(token, "04", 10, quien)
        val pedido = order(token)
        val linea = lineId(token, pedido)
        packageLine(token, pedido, linea, 10, estante)

        val response = put("/v1/orders/$pedido/lines/$linea/assign", mapOf<String, Any?>(), token)

        // Assigning work on goods that already left would mean someone is going to take them out
        // dos veces.
        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
    }

    @Test
    fun `assigning requires the permission and an existing user and location`() {
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
