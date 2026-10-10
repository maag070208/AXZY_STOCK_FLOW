package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Receiving (M03) end to end over real HTTP.
 *
 * The names R1…R8 are those of docs/flujos/recepcion.md §6. Here R2–R6 and R8 are tested;
 * **R1 (a line with a discrepancy cannot be located) and R7 (locating does not change the
 * total) depend on the locate flow**, which is the M02 inventory route: R7
 * is covered there (`InventoryFlowTest` A1) and R1 is the missing integration
 * —the locate route still does not receive the invoice line—, documented as
 * pending in the plan.
 */
class ReceivingFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun supervisor(): String {
        createUser("supervisor", USER_PASSWORD, jefa)
        return accessTokenOf("supervisor", USER_PASSWORD)
    }

    private fun product(token: String, code: String = "ABRAA1B") =
        post("/v1/products", mapOf("productCode" to code, "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

    /** An invoice with one line: 10 units, boxes of 5. */
    private fun invoice(
        token: String,
        number: String = "F-2026-001",
        quantity: Int = 10,
        unitsPerBox: Int = 5,
        lines: Int = 1,
    ): Long {
        val body = mapOf(
            "invoiceNumber" to number,
            "receiptDate" to "2026-10-07",
            "invoiceType" to "NATIONAL",
            "lines" to (1..lines).map {
                mapOf(
                    "productCode" to "ABRAA1B",
                    "container" to "CONT-0$it",
                    "unitMeasure" to "PZA",
                    "quantity" to quantity,
                    "boxQuantity" to quantity / unitsPerBox,
                    "unitsPerBox" to unitsPerBox,
                )
            },
        )
        val response = post("/v1/invoices", body, token)
        assertThat(response.status).describedAs("alta de factura: ${response.raw}").isEqualTo(201)
        return response.at<Int>("id").toLong()
    }

    private fun lineIds(token: String, invoiceId: Long): List<Long> =
        get("/v1/invoices/$invoiceId", token).at<List<Map<String, Any?>>>("lines").map { (it["id"] as Number).toLong() }

    private fun capture(token: String, invoiceId: Long, lineId: Long, quantity: Int, boxes: Int) =
        post(
            "/v1/invoices/$invoiceId/lines/$lineId/capture",
            mapOf("physicalQuantity" to quantity, "physicalBoxQuantity" to boxes),
            token,
        )

    private fun decide(token: String, invoiceId: Long, route: String, reason: String = "SUPPLIER_ERROR") =
        post("/v1/invoices/$invoiceId/$route", mapOf("adjustmentReason" to reason, "note" to "Revisado"), token)

    /** Leaves balance in receiving: it is where a location draws from. */
    /** Locates goods from receiving onto a shelf, saying which line they come from. */
    private fun locate(
        token: String,
        lineId: Long?,
        to: String = "04-B-C",
        quantity: Int,
        from: String = "RECEIVING",
    ) = post(
        "/v1/inventory/locate",
        buildMap<String, Any?> {
            put("productCode", "ABRAA1B")
            put("fromLocationCode", from)
            put("toLocationCode", to)
            put("quantity", quantity)
            if (lineId != null) put("invoiceLineId", lineId)
        },
        token,
    )

    private fun shelf(token: String) =
        post("/v1/locations", mapOf("warehouseId" to 1, "hall" to "04", "code" to "B", "position" to "C"), token)

    /** The approver of increases: any person other than the one who requests them. */
    private fun approver(token: String): Long {
        val existing = db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).list()
        return existing.firstOrNull() ?: supervisor().let {
            db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).single()
        }
    }

    private fun quantityAt(token: String, location: String): String? {
        val items = get("/v1/inventory/stock?onlyWithStock=false&locationCode=$location", token)
            .at<List<Map<String, Any?>>>("items")
        return items.firstOrNull()?.get("quantity") as String?
    }

    private fun line(token: String, invoiceId: Long, lineId: Long): Map<String, Any?> =
        get("/v1/invoices/$invoiceId", token).at<List<Map<String, Any?>>>("lines")
            .first { (it["id"] as Number).toLong() == lineId }

    // ── Creation and query ─────────────────────────────────────────────────

    @Test
    fun `creating answers 201 with the reference and the detail includes the lines`() {
        val token = manager()
        product(token)

        val id = invoice(token)
        val detail = get("/v1/invoices/$id", token)

        assertThat(detail.at<String>("invoiceNumber")).isEqualTo("F-2026-001")
        assertThat(detail.at<String>("status")).isEqualTo("PENDING")
        assertThat(detail.at<Int>("totalLines")).isEqualTo(1)
        assertThat(detail.at<Int>("pendingApprovalLines")).isEqualTo(1)
        assertThat(detail.at<List<Map<String, Any?>>>("lines").single()["quantity"]).isEqualTo("10.000")
    }

    @Test
    fun `an invoice without lines and a repeated number are rejected`() {
        val token = manager()
        product(token)

        val sinLineas = post(
            "/v1/invoices",
            mapOf("invoiceNumber" to "F-1", "receiptDate" to "2026-10-07", "lines" to emptyList<Any>()),
            token,
        )
        assertThat(sinLineas.status).isEqualTo(422)
        assertThat(sinLineas.code).isEqualTo("FIELD_REQUIRED")

        invoice(token, number = "F-REPE")
        val repetida = post(
            "/v1/invoices",
            mapOf(
                "invoiceNumber" to "F-REPE",
                "receiptDate" to "2026-10-07",
                "lines" to listOf(
                    mapOf("productCode" to "ABRAA1B", "quantity" to 5, "unitsPerBox" to 5),
                ),
            ),
            token,
        )
        assertThat(repetida.status).isEqualTo(409)
        assertThat(repetida.code).isEqualTo("DUPLICATE_INVOICE_NUMBER")
    }

    @Test
    fun `a product that does not exist and a badly formed date are rejected`() {
        val token = manager()
        product(token)

        val producto = post(
            "/v1/invoices",
            mapOf(
                "invoiceNumber" to "F-2",
                "receiptDate" to "2026-10-07",
                "lines" to listOf(mapOf("productCode" to "NOEXISTE", "quantity" to 5, "unitsPerBox" to 5)),
            ),
            token,
        )
        val fecha = post(
            "/v1/invoices",
            mapOf(
                "invoiceNumber" to "F-3",
                "receiptDate" to "07/10/2026",
                "lines" to listOf(mapOf("productCode" to "ABRAA1B", "quantity" to 5, "unitsPerBox" to 5)),
            ),
            token,
        )

        assertThat(producto.code).isEqualTo("INVALID_PRODUCT_CODE")
        assertThat(fecha.status).isEqualTo(400)
        assertThat(fecha.code).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `an invoice that does not exist answers INVOICE_NOT_FOUND, and so does a line`() {
        val token = manager()
        product(token)
        val id = invoice(token)

        assertThat(get("/v1/invoices/999999", token).code).isEqualTo("INVOICE_NOT_FOUND")

        val linea = capture(token, id, 999999, quantity = 10, boxes = 2)
        assertThat(linea.status).isEqualTo(404)
        assertThat(linea.code).isEqualTo("LINE_NOT_FOUND")
    }

    // ── R2: every difference requires approval, with no threshold ──────────

    @Test
    fun `R2 - a difference of one unit leaves the line in DISCREPANCY`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        // 10 were invoiced and 9 arrived: a one-unit difference.
        val response = capture(token, id, lineId, quantity = 9, boxes = 1)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("DISCREPANCY")
        assertThat(response.at<String>("difference")).isEqualTo("-1.000")
        // The header's counter says the same as the line: it is what prevents closing.
        assertThat(get("/v1/invoices/$id", token).at<Int>("pendingApprovalLines")).isEqualTo(1)
    }

    @Test
    fun `an exact count leaves the line in CAPTURED and does not request approval`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        val response = capture(token, id, lineId, quantity = 10, boxes = 2)

        assertThat(response.at<String>("status")).isEqualTo("CAPTURED")
        assertThat(response.at<String>("difference")).isEqualTo("0.000")
        // Without blocking lines the invoice moves to "pending locate".
        assertThat(get("/v1/invoices/$id", token).at<String>("status")).isEqualTo("PENDING_TO_LOCATE")
        assertThat(get("/v1/invoices/$id", token).at<Int>("pendingApprovalLines")).isZero()
    }

    @Test
    fun `a count with inconsistent boxes is rejected`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        // 9 pieces cannot be 2 boxes of 5: the data contradicts itself.
        val response = capture(token, id, lineId, quantity = 9, boxes = 2)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INCONSISTENT_BOX_COUNT")
    }

    // ── R3 and R4: who approves and who covers ────────────────────────────

    @Test
    fun `R3 - whoever counted cannot approve their own discrepancy`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        // The same user who counted (is an administrator, has the permission).
        val response = decide(token, id, "approve")

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("SEGREGATION_OF_DUTIES")
        // And the line stays as it was: nothing was approved.
        assertThat(get("/v1/invoices/$id", token).at<List<Map<String, Any?>>>("lines").single()["status"])
            .isEqualTo("DISCREPANCY")
    }

    @Test
    fun `R4 - one approval covers all the discrepancies of the invoice`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token, lines = 2)
        lineIds(token, id).forEach { capture(token, id, it, quantity = 9, boxes = 1) }

        val response = decide(jefe, id, "approve")
        val lines = response.at<List<Map<String, Any?>>>("lines")

        assertThat(response.status).isEqualTo(200)
        assertThat(lines.map { it["status"] }).containsOnly("APPROVED")
        // The same approver in both, and with their name: the audit log is read without ids.
        val approvers = lines.map { it["approvedByName"] }.distinct()
        assertThat(approvers).hasSize(1)
        assertThat(approvers.single()).isEqualTo("Usuario supervisor")
        assertThat(lines.map { it["adjustmentReason"] }).containsOnly("SUPPLIER_ERROR")
    }

    @Test
    fun `rejecting a discrepancy returns the line to counting`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = decide(jefe, id, "reject-discrepancy")
        val line = response.at<List<Map<String, Any?>>>("lines").single()

        assertThat(response.status).isEqualTo(200)
        assertThat(line["status"]).isEqualTo("PENDING")
        // The count was cleared: the reason for the rejection is that it has to be counted again.
        assertThat(line["physicalQuantity"]).isNull()
        assertThat(line["capturedByName"]).isNull()
    }

    @Test
    fun `a decision without a reason answers REASON_REQUIRED`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = post("/v1/invoices/$id/approve", mapOf("note" to "sin motivo"), jefe)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
    }

    // ── R5 and R6: closing and approval with nothing pending ──────────────

    @Test
    fun `R5 - an invoice with unresolved lines is not closed`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = post("/v1/invoices/$id/close", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INVOICE_HAS_PENDING_LINES")
        assertThat(response.at<Map<String, String>>("details")["pendingLines"]).isEqualTo("1")
    }

    @Test
    fun `R5 - it closes when nothing blocks it, and does not close twice`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val cerrada = post("/v1/invoices/$id/close", emptyMap<String, Any>(), token)
        val otraVez = post("/v1/invoices/$id/close", emptyMap<String, Any>(), token)

        assertThat(cerrada.status).isEqualTo(200)
        assertThat(cerrada.at<String>("status")).isEqualTo("COMPLETED")
        assertThat(otraVez.status).isEqualTo(409)
        assertThat(otraVez.code).isEqualTo("INVOICE_ALREADY_CLOSED")
    }

    @Test
    fun `R6 - approving an invoice without discrepancies answers NO_PENDING_APPROVAL`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val response = decide(jefe, id, "approve")

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("NO_PENDING_APPROVAL")
    }

    // ── The pending inbox ─────────────────────────────────────────────────────

    @Test
    fun `the pending queue brings only what awaits a decision`() {
        val token = manager()
        product(token)
        val conDiscrepancia = invoice(token, number = "F-DISC")
        val sinDiscrepancia = invoice(token, number = "F-OK")
        capture(token, conDiscrepancia, lineIds(token, conDiscrepancia).single(), quantity = 9, boxes = 1)
        capture(token, sinDiscrepancia, lineIds(token, sinDiscrepancia).single(), quantity = 10, boxes = 2)

        val response = get("/v1/invoices/pending-approval", token)
        val items = response.at<List<Map<String, Any?>>>("items")

        // No pagination envelope: it is `{ items: [...] }` and nothing else.
        assertThat(response.raw).contains("\"items\"").doesNotContain("totalPages")
        assertThat(items).hasSize(1)
        assertThat(items.single()["invoiceNumber"]).isEqualTo("F-DISC")
        assertThat(items.single()["pendingLines"]).isEqualTo(1)
        // The sum of ABSOLUTE differences: positive even when goods are missing.
        assertThat(items.single()["totalDifference"]).isEqualTo("1.000")
    }

    @Test
    fun `the list filters by status and rejects a negative page`() {
        val token = manager()
        product(token)
        val id = invoice(token)
        capture(token, id, lineIds(token, id).single(), quantity = 9, boxes = 1)

        assertThat(get("/v1/invoices?status=IN_PROGRESS", token).at<Int>("totalItems")).isEqualTo(1)
        assertThat(get("/v1/invoices?status=COMPLETED", token).at<Int>("totalItems")).isZero()

        // R8: the value is rejected instead of being silently truncated.
        val response = get("/v1/invoices?page=-1", token)
        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PAGINATION")
    }

    @Test
    fun `a non-existent status is rejected with the allowed list`() {
        val token = manager()

        val response = get("/v1/invoices?status=LO_QUE_SEA", token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
        assertThat(response.at<Map<String, String>>("details")["allowed"]).contains("IN_PROGRESS")
    }

    // ── R1 and R7: locating what comes from an invoice ─────────────────────

    @Test
    fun `R1 - a discrepant line cannot be located and the inventory does not change`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        // 10 were invoiced and 9 were counted: a discrepancy not yet approved.
        capture(token, id, lineId, quantity = 9, boxes = 1)

        val response = locate(token, lineId, quantity = 9)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("DISCREPANCY_NOT_APPROVED")
        // The inventory did not change: everything is still in receiving and nothing on the shelf.
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("9.000")
        assertThat(quantityAt(token, "04-B-C")).isNull()
        // And the line is still waiting for a decision.
        assertThat(line(token, id, lineId)["status"]).isEqualTo("DISCREPANCY")
    }

    @Test
    fun `R7 - locating from the invoice leaves the line located and the total the same`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val response = locate(token, lineId, quantity = 10)
        val line = line(token, id, lineId)

        assertThat(response.status).isEqualTo(201)
        assertThat(line["status"]).isEqualTo("LOCATED")
        assertThat(line["locatedQuantity"]).isEqualTo("10.000")
        assertThat(line["locationDisplayName"]).isEqualTo("04-B-C")
        // The product total does not change: it leaves receiving and enters the shelf.
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("0.000")
        assertThat(quantityAt(token, "04-B-C")).isEqualTo("10.000")
        // The catalog sends quantities as a number (its documented exception).
        assertThat(
            (get("/v1/products?q=ABRAA1B", token).at<List<Map<String, Any?>>>("items").single()["stock"] as Number).toDouble(),
        ).isEqualTo(10.0)
    }

    @Test
    fun `locating partially does not close the line nor approve it`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        locate(token, lineId, quantity = 4)
        val line = line(token, id, lineId)

        // Still counted: there was no discrepancy to approve, and the database forbids
        // `APPROVED` without an approver.
        assertThat(line["status"]).isEqualTo("CAPTURED")
        assertThat(line["locatedQuantity"]).isEqualTo("4.000")
    }

    @Test
    fun `a line cannot be located before it is counted`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()

        val response = locate(token, lineId, quantity = 10)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
        assertThat(quantityAt(token, "04-B-C")).isNull()
    }

    @Test
    fun `locating a line that does not exist or belongs to another product is rejected`() {
        val token = manager()
        product(token)
        shelf(token)
        val id = invoice(token)
        val lineId = lineIds(token, id).single()
        capture(token, id, lineId, quantity = 10, boxes = 2)

        val inexistente = locate(token, lineId = 999999, quantity = 1)
        assertThat(inexistente.status).isEqualTo(404)
        assertThat(inexistente.code).isEqualTo("LINE_NOT_FOUND")

        // Moving one product's balance while another product's line is recorded would leave the
        // invoice saying one thing and the warehouse another.
        product(token, code = "OTRO01")
        val otro = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "OTRO01", "fromLocationCode" to "RECEIVING",
                "toLocationCode" to "04-B-C", "quantity" to 1, "invoiceLineId" to lineId,
            ),
            token,
        )
        assertThat(otro.status).isEqualTo(400)
        assertThat(otro.code).isEqualTo("INVALID_PARAMETER")
    }

    // ── Permissions per route ─────────────────────────────────────────────────

    @Test
    fun `a warehouse operator does not capture or approve invoices`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        // The floor operator looks up invoices —he needs them to locate— but does not
        // create or close them: that belongs to the counter and the supervisor.
        assertThat(get("/v1/invoices", token).status).isEqualTo(200)
        assertThat(post("/v1/invoices", mapOf("invoiceNumber" to "F-X"), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/invoices/1/close", emptyMap<String, Any>(), token).code).isEqualTo("PERMISSION_DENIED")
    }

    // ── M10/M03: the goods enter on counting ───────────────────────────────

    private fun kardexOf(token: String, moveType: String): List<Map<String, Any?>> =
        get("/v1/inventory/kardex?moveType=$moveType", token).at<List<Map<String, Any?>>>("items")

    @Test
    fun `when counting, the goods enter RECEIVING`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()

        val response = capture(token, factura, linea, quantity = 10, boxes = 2)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // Before this, counting put nothing in: the balance had to appear through a manual
        // adjustment, and locating from RECEIVING found zero.
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("10.000")
        val entradas = kardexOf(token, "RECEIPT_IN")
        assertThat(entradas).hasSize(1)
        assertThat(entradas.single()["units"]).isEqualTo("10.000")
    }

    @Test
    fun `counting again does not add twice`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        // 9 of 10 invoiced were counted: a discrepancy, which the supervisor sends to a recount.
        capture(token, factura, linea, quantity = 9, boxes = 1)
        val rechazo = post(
            "/v1/invoices/$factura/reject-discrepancy",
            mapOf("adjustmentReason" to "COUNT_ERROR", "note" to "Recuento pedido"),
            jefe,
        )
        assertThat(rechazo.status).describedAs(rechazo.raw).isEqualTo(200)

        val response = capture(token, factura, linea, quantity = 8, boxes = 1)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // 8 are left: 9 entered and the recount took out 1 with a counting-error adjustment. Without
        // the count of what was already entered, the ledger would have 17 and the stock would not match anything.
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("8.000")
        assertThat(kardexOf(token, "RECEIPT_IN")).hasSize(1)
        val ajustes = kardexOf(token, "ADJUSTMENT_OUT")
        assertThat(ajustes.single()["units"]).isEqualTo("1.000")
    }

    @Test
    fun `counting too many records the difference, not the total`() {
        val token = manager()
        val jefe = supervisor()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        capture(token, factura, linea, quantity = 9, boxes = 1)
        post(
            "/v1/invoices/$factura/reject-discrepancy",
            mapOf("adjustmentReason" to "COUNT_ERROR", "note" to "Recuento pedido"),
            jefe,
        )

        val response = capture(token, factura, linea, quantity = 14, boxes = 2)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // 9 + 5: the second entry is the difference, not the 14 again.
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("14.000")
        assertThat(kardexOf(token, "RECEIPT_IN").map { it["units"] }).containsExactly("9.000", "5.000")
    }

    // ── Assigning the count to a person (M03) ──────────────────────────────

    @Test
    fun `assigning a line records who counts it and it is seen in the detail`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        val capturista = createUser("capturista", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)

        val response = put("/v1/invoices/$factura/lines/$linea/assign", mapOf("userId" to capturista), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("ASSIGNED")
        assertThat((response.at<Number>("assignedUserId")).toLong()).isEqualTo(capturista)
        // The visible name, so the client does not have to resolve it.
        assertThat(response.at<String>("assignedUserName")).isEqualTo("Usuario capturista")
        // And the invoice detail publishes it.
        assertThat(line(token, factura, linea)["assignedUserName"]).isEqualTo("Usuario capturista")
    }

    @Test
    fun `without userId the assignment is to the caller`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        val jefa = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()

        val response = put("/v1/invoices/$factura/lines/$linea/assign", mapOf<String, Any?>(), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat((response.at<Number>("assignedUserId")).toLong()).isEqualTo(jefa)
    }

    @Test
    fun `a line already counted is not reassigned`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        capture(token, factura, linea, quantity = 10, boxes = 2)

        val response = put("/v1/invoices/$factura/lines/$linea/assign", mapOf<String, Any?>(), token)

        // The count has a responsible person: changing it later would be rewriting who did the work.
        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("INVALID_TRANSITION")
    }

    @Test
    fun `assigning requires the permission and an existing user`() {
        val token = manager()
        product(token)
        val factura = invoice(token)
        val linea = lineIds(token, factura).single()
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val suToken = accessTokenOf("operador", USER_PASSWORD)

        val sinPermiso = put("/v1/invoices/$factura/lines/$linea/assign", mapOf<String, Any?>(), suToken)
        val inventado = put("/v1/invoices/$factura/lines/$linea/assign", mapOf("userId" to 999999), token)

        assertThat(sinPermiso.code).isEqualTo("PERMISSION_DENIED")
        assertThat(inventado.status).isEqualTo(422)
        assertThat(inventado.code).isEqualTo("ASSIGNEE_NOT_FOUND")
    }
}
