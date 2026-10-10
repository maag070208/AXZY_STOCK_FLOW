package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiResponse
import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Stock (M02) end to end over real HTTP.
 *
 * The names A1…A10 are those of docs/flujos/existencias-y-ajustes.md §7. The
 * concurrency (A3, A4) and the immutability of the ledger (A9) are in
 * `InventoryEngineConcurrencyTest`, against the database and with two real
 * transactions; repeating them over HTTP would add nothing.
 */
class InventoryFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    /** A second user: every increase needs **another person** to approve it. */
    private fun approver(): Long = createUser("supervisor", USER_PASSWORD, jefa)

    private fun adjust(token: String, body: Map<String, Any?>, key: UUID? = null) =
        post(
            "/v1/inventory/adjustments",
            body,
            token,
            if (key == null) emptyMap() else mapOf("Idempotency-Key" to key.toString()),
        )

    /** Leaves stock in a location: it is the only path, and it goes through the engine. */
    private fun stockIn(token: String, location: String, quantity: Int, who: Long): ApiResponse =
        adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to location,
                "quantity" to quantity,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
                "approverId" to who,
            ),
        )

    private fun createProduct(token: String, code: String = "ABRAA1B") =
        post("/v1/products", mapOf("productCode" to code, "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

    private fun createShelf(token: String, hall: String) =
        post(
            "/v1/locations",
            mapOf("warehouseId" to 1, "hall" to hall, "code" to "B", "position" to "C"),
            token,
        )

    private fun quantityAt(token: String, location: String): String? {
        val items = get("/v1/inventory/stock?onlyWithStock=false&locationCode=$location", token)
            .at<List<Map<String, Any?>>>("items")
        return items.firstOrNull()?.get("quantity") as String?
    }

    // ── A1: locating does not change the total ─────────────────────────────

    @Test
    fun `A1 - locating moves stock without changing the total and records a LOCATE movement`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        assertThat(stockIn(token, "RECEIVING", 1000, who).status).isEqualTo(201)

        val located = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "ABRAA1B",
                "fromLocationCode" to "RECEIVING",
                "toLocationCode" to "04-B-C",
                "quantity" to 300,
            ),
            token,
        )

        assertThat(located.status).isEqualTo(201)
        assertThat(located.at<String>("status")).isEqualTo("APPLIED")
        // The total does not travel in a transfer response: returning it would invite
        // believing that it changed.
        assertThat(located.at<String?>("total")).isNull()

        assertThat(quantityAt(token, "04-B-C")).isEqualTo("300.000")
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("700.000")

        val kardex = get("/v1/inventory/kardex?moveType=LOCATE", token)
        assertThat(kardex.at<Int>("totalItems")).isEqualTo(1)
        assertThat(kardex.at<List<Map<String, Any?>>>("items").single()["toLocationDisplayName"]).isEqualTo("04-B-C")
    }

    // ── A2: you cannot take out more than there is ────────────────────────────

    @Test
    fun `A2 - a shrinkage larger than the balance answers INSUFFICIENT_STOCK with what is available`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        stockIn(token, "04-B-C", 100, who)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "04-B-C",
                "quantity" to 150,
                "direction" to "DECREASE",
                "reason" to "SHORTAGE",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INSUFFICIENT_STOCK")
        // The detail says HOW MUCH there is: an "insufficient stock" without numbers forces
        // looking at the database to know what happened.
        assertThat(response.at<Map<String, String>>("details")["available"]).isEqualTo("100")
        assertThat(quantityAt(token, "04-B-C")).isEqualTo("100.000")
    }

    // ── A5: a shrinkage requires its reason ─────────────────────────────────

    @Test
    fun `A5 - an adjustment without a reason answers REASON_REQUIRED and writes nothing`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEIVING", 100, who)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 10,
                "direction" to "DECREASE",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("100.000")
    }

    @Test
    fun `a reason that does not exist answers INVALID_PARAMETER with the allowed list`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEIVING", 100, who)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 10,
                "direction" to "DECREASE",
                "reason" to "PORQUE_SI",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
        assertThat(response.at<Map<String, String>>("details")["allowed"]).contains("THEFT")
    }

    // ── A6: the permission depends on the reason ────────────────────────────

    @Test
    fun `A6 - the permission for an adjustment depends on its reason`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEIVING", 100, who)

        // An operator who can correct a count but not declare a theft.
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        db.sql(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            SELECT r.id, p.id, 'ALL' FROM roles r, permissions p
            WHERE r.key = :role AND p.key IN ('inventory.view', 'inventory.adjust_count_error')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("role", BaseRole.WAREHOUSE_OPERATOR.key.value).update()
        val operator = accessTokenOf("operador", USER_PASSWORD)

        val theft = adjust(
            operator,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 1,
                "direction" to "DECREASE",
                "reason" to "THEFT",
                "origin" to "MANUAL_COUNT",
                "note" to "Faltó una caja del estante",
            ),
        )
        val countError = adjust(
            operator,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 1,
                "direction" to "DECREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(theft.status).isEqualTo(403)
        assertThat(theft.code).isEqualTo("PERMISSION_DENIED")
        assertThat(countError.status).isEqualTo(201)
    }

    // ── A7: origin and destination are different ───────────────────────────

    @Test
    fun `A7 - locating to the same shelf answers SAME_SOURCE_AND_DESTINATION`() {
        val token = manager()
        createProduct(token)

        val response = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "ABRAA1B",
                "fromLocationCode" to "RECEIVING",
                "toLocationCode" to "RECEIVING",
                "quantity" to 10,
            ),
            token,
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("SAME_SOURCE_AND_DESTINATION")
    }

    @Test
    fun `locating from a shelf is rejected - that is what moving is for`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        stockIn(token, "04-B-C", 50, who)
        createShelf(token, "05")

        val response = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "ABRAA1B",
                "fromLocationCode" to "04-B-C",
                "toLocationCode" to "05-B-C",
                "quantity" to 10,
            ),
            token,
        )

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
    }

    // ── A8: idempotencia ────────────────────────────────────────────────────

    @Test
    fun `A8 - resending with the same key does not duplicate the movement`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEIVING", 100, who)
        val key = UUID.randomUUID()

        val body = mapOf(
            "productCode" to "ABRAA1B",
            "locationCode" to "RECEIVING",
            "quantity" to 10,
            "direction" to "DECREASE",
            "reason" to "COUNT_ERROR",
            "origin" to "MANUAL_COUNT",
        )
        val first = adjust(token, body, key)
        val second = adjust(token, body, key)

        assertThat(first.status).isEqualTo(201)
        assertThat(second.status).isEqualTo(201)
        // The same response: the same movement.
        assertThat(second.at<List<Int>>("kardexIds")).isEqualTo(first.at<List<Int>>("kardexIds"))
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("90.000")
        assertThat(db.sql("SELECT count(*) FROM kardex").query(Int::class.java).single()).isEqualTo(2)
    }

    @Test
    fun `an idempotency key that is not a UUID answers INVALID_IDEMPOTENCY_KEY`() {
        val token = manager()
        createProduct(token)

        val response = post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 1,
                "direction" to "DECREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
            ),
            token,
            mapOf("Idempotency-Key" to "no-es-un-uuid"),
        )

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_IDEMPOTENCY_KEY")
    }

    // ── A10: the count uses the same filter as the listing ────────────────

    @Test
    fun `A10 - a range with no movements returns empty items and zero totalItems`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEIVING", 10, who)

        val response = get("/v1/inventory/kardex?from=2020-01-01&to=2020-01-31", token)

        assertThat(response.at<List<Any>>("items")).isEmpty()
        assertThat(response.at<Int>("totalItems")).isZero()
    }

    @Test
    fun `an inverted range is rejected instead of returning nothing`() {
        val token = manager()

        val response = get("/v1/inventory/kardex?from=2026-10-31&to=2026-10-01", token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_FILTER_RANGE")
    }

    // ── Approval and audit log ──────────────────────────────────────────────

    @Test
    fun `an increase without an approver stays pending and does not touch the inventory`() {
        val token = manager()
        createProduct(token)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 500,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
            ),
        )

        // 202: accepted, pending. It is not an error and it is not a movement.
        assertThat(response.status).isEqualTo(202)
        assertThat(response.at<String>("status")).isEqualTo("PENDING_APPROVAL")
        assertThat(quantityAt(token, "RECEIVING")).isNull()
        assertThat(db.sql("SELECT count(*) FROM kardex").query(Int::class.java).single()).isZero()
    }

    @Test
    fun `whoever requests an increase cannot approve it themselves`() {
        val token = manager()
        val me = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()
        createProduct(token)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 500,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
                "approverId" to me,
            ),
        )

        // 403 and not 422: `SEGREGATION_OF_DUTIES` belongs to the authorization family
        // (docs/api/errores.md), and the client does the same as with any denial:
        // have another person approve it.
        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("SEGREGATION_OF_DUTIES")
    }

    @Test
    fun `every movement writes to the audit log within the same transaction`() {
        val token = manager()
        val who = approver()
        createProduct(token)

        stockIn(token, "RECEIVING", 100, who)

        val registros = db.sql("SELECT count(*) FROM audit_log WHERE action = 'INVENTORY_ADJUSTED'")
            .query(Int::class.java).single()
        assertThat(registros).isEqualTo(1)

        // And the movement the audit log talks about exists: if the transaction had
        // been rolled back, there would be an audit trail of something that did not happen.
        assertThat(db.sql("SELECT count(*) FROM kardex").query(Int::class.java).single()).isEqualTo(1)
    }

    // ── The stock listing ────────────────────────────────────────────────────

    @Test
    fun `the stock list filters by product and by location`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        stockIn(token, "RECEIVING", 100, who)
        stockIn(token, "04-B-C", 40, who)

        val all = get("/v1/inventory/stock?onlyWithStock=false", token)
        val byLocation = get("/v1/inventory/stock?locationCode=04-B-C", token)

        assertThat(all.at<Int>("totalItems")).isEqualTo(2)
        assertThat(byLocation.at<Int>("totalItems")).isEqualTo(1)
        assertThat(byLocation.at<List<Map<String, Any?>>>("items").single()["locationDisplayName"]).isEqualTo("04-B-C")
        // The quantity travels as a string with three decimals, which is the general rule.
        assertThat(byLocation.at<List<Map<String, Any?>>>("items").single()["quantity"]).isEqualTo("40.000")
    }

    @Test
    fun `the unassigned balance is found by its visible name`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "UNASSIGNED", 250, who)

        val response = get("/v1/inventory/stock?locationCode=UNASSIGNED", token)

        assertThat(response.at<List<Map<String, Any?>>>("items").single()["locationDisplayName"]).isEqualTo("UNASSIGNED")
        assertThat(response.at<List<Map<String, Any?>>>("items").single()["quantity"]).isEqualTo("250.000")
    }

    // ── Approval: the permission is checked, not assumed ───────────────────

    /**
     * A custom role: it can adjust, **but cannot approve**.
     *
     * It is the way to check the gap without depending on which permissions each base role has today.
     */
    private fun ajustador(token: String, key: String, quien: String): Long {
        val rol = post(
            "/v1/roles",
            mapOf(
                "key" to key,
                "name" to "Ajustador",
                "permissions" to listOf(
                    mapOf("permissionKey" to "inventory.adjust_count_error", "scope" to "ALL"),
                    mapOf("permissionKey" to "inventory.adjust_theft", "scope" to "ALL"),
                ),
            ),
            token,
        )
        assertThat(rol.status).describedAs(rol.raw).isEqualTo(201)
        return createUser(quien, USER_PASSWORD, key)
    }

    @Test
    fun `an approver without the approval permission approves nothing`() {
        val token = manager()
        createProduct(token)
        ajustador(token, "AJUSTADOR", "operador")
        val compinche = ajustador(token, "AJUSTADOR2", "compinche")
        val suToken = accessTokenOf("operador", USER_PASSWORD)

        // The operator signs off their own theft with another adjuster's identifier: neither of
        // the two has `inventory.approve_adjustment`, which is what the catalog requires to
        // approve. Before, this used to be applied.
        val response = post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 5,
                "direction" to "DECREASE",
                "reason" to "THEFT",
                "origin" to "MANUAL_COUNT",
                "note" to "Faltó mercancía del anaquel 04",
                "approverId" to compinche,
            ),
            suToken,
        )

        assertThat(response.status).describedAs(response.raw).isEqualTo(403)
        assertThat(response.code).isEqualTo("APPROVER_NOT_AUTHORIZED")
        assertThat(response.at<Map<String, String>>("details")["permission"]).isEqualTo("inventory.approve_adjustment")
        // And nothing moved: the approval that is missing leaves no trace in the ledger.
        val kardex = get("/v1/inventory/kardex?moveType=ADJUSTMENT_OUT", token)
        assertThat(kardex.status).describedAs(kardex.raw).isEqualTo(200)
        assertThat(kardex.at<Int>("totalItems")).isZero()
    }

    @Test
    fun `with an approver who can, the adjustment is applied`() {
        val token = manager()
        val supervisora = approver()
        createProduct(token)
        ajustador(token, "AJUSTADOR", "operador")
        val suToken = accessTokenOf("operador", USER_PASSWORD)
        // Whoever requests leaves stock, and another person approves it: the boss requests, the
        // supervisora aprueba.
        assertThat(stockIn(token, "RECEIVING", 50, supervisora).status).isEqualTo(201)

        val response = post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEIVING",
                "quantity" to 5,
                "direction" to "DECREASE",
                "reason" to "THEFT",
                "origin" to "MANUAL_COUNT",
                "note" to "Faltó mercancía del anaquel 04",
                "approverId" to supervisora,
            ),
            suToken,
        )

        // operator requests and the supervisor —who does have the permission— approves.
        assertThat(response.status).describedAs(response.raw).isEqualTo(201)
        assertThat(response.at<String>("status")).isEqualTo("APPLIED")
        assertThat(quantityAt(token, "RECEIVING")).isEqualTo("45.000")
    }
}
