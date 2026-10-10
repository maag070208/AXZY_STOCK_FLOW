package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The transfer engine (V13) against real PostgreSQL.
 *
 * The acceptance tests of the flow that are **of the engine**, not of
 * the route are demonstrated here: T1, T2, T5 and T6, plus the invariant that summarizes all of them —
 * `Σ stock_by_location + Σ stock_in_transit` is preserved on every transition—. The
 * API ones (T3, T4, T7, T8) go with the routes.
 *
 * The engine lives in SQL because a transfer moves balance, and the balance is moved by the engine:
 * an `UPDATE stock_by_location` from Kotlin would be the second write path that
 * `InventoryWriteGuardTest` forbids.
 */
class TransferEngineTest {

    private val db = TestPersistence()

    private var userId = 0L
    private var productId = 0L
    private var origin = 0L
    private var destinationWarehouse = 0L
    private var destination = 0L
    private var transferId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        // The ledger records who moved: without a user, the foreign key rejects it.
        userId = db.asOwner.sql(
            "INSERT INTO users (username, password_hash, name) VALUES ('motor', 'x', 'Motor') RETURNING id",
        ).query(Long::class.java).single()
        productId = db.asOwner.sql(
            "INSERT INTO products (product_code, name, unit_measure_id) VALUES ('TRF001', 'Traslado', 1) RETURNING id",
        ).query(Long::class.java).single()
        origin = db.asOwner.sql("SELECT id FROM locations WHERE kind = 'RECEIVING' AND warehouse_id = 1")
            .query(Long::class.java).single()

        destinationWarehouse = db.asOwner.sql(
            "INSERT INTO warehouses (code, name, is_default, active) VALUES ('BRANCH', 'Sucursal', FALSE, TRUE) RETURNING id",
        ).query(Long::class.java).single()
        destination = db.asOwner.sql(
            """
            INSERT INTO locations (warehouse_id, hall, code, position, levels, kind)
            VALUES (:warehouse, 'RECEIVING', 'RECEIVING', 'RECEIVING', 1, 'RECEIVING') RETURNING id
            """.trimIndent(),
        ).param("warehouse", destinationWarehouse).query(Long::class.java).single()

        // 100 units in the origin's receiving, by the engine.
        db.jdbc.sql(
            """
            SELECT record_movement(:product, 1, 'INITIAL', 100, NULL, :location, NULL, NULL, NULL, NULL, :user, NULL, NULL)
            """.trimIndent(),
        ).param("product", productId).param("location", origin).param("user", userId).query(Long::class.java).single()
    }

    /** A draft transfer with one line of the requested quantity. */
    private fun draft(quantity: Int, code: String = "TR-2026-0001"): Long {
        val id = db.asOwner.sql(
            "INSERT INTO transfers (code, from_warehouse_id, to_warehouse_id) VALUES (:code, 1, :to) RETURNING id",
        ).param("code", code).param("to", destinationWarehouse).query(Long::class.java).single()
        db.asOwner.sql(
            """
            INSERT INTO transfer_lines (transfer_id, product_id, quantity)
            VALUES (:transfer, :product, :quantity)
            """.trimIndent(),
        ).param("transfer", id).param("product", productId).param("quantity", quantity).update()
        return id
    }

    private fun dispatch(id: Long = transferId): Int =
        db.jdbc.sql("SELECT dispatch_transfer(:id, :user)").param("id", id).param("user", userId)
            .query(Int::class.java).single()

    private fun receive(id: Long = transferId): Int =
        db.jdbc.sql("SELECT receive_transfer(:id, :user)").param("id", id).param("user", userId)
            .query(Int::class.java).single()

    private fun cancel(id: Long = transferId): Int =
        db.jdbc.sql("SELECT cancel_transfer(:id, :user)").param("id", id).param("user", userId)
            .query(Int::class.java).single()

    private fun stockAt(locationId: Long): Quantity =
        db.jdbc.sql("SELECT COALESCE(quantity, 0) FROM stock_by_location WHERE product_id = :product AND location_id = :location")
            .param("product", productId).param("location", locationId)
            .query(BigDecimal::class.java).optional().orElse(BigDecimal.ZERO)
            .let { Quantity.ofScaled(it.movePointRight(3).longValueExact()) }

    private fun inTransit(): Quantity =
        db.jdbc.sql(
            "SELECT COALESCE(sum(quantity), 0) FROM stock_in_transit WHERE product_id = :product AND received_at IS NULL",
        ).param("product", productId).query(BigDecimal::class.java).single()
            .let { Quantity.ofScaled(it.movePointRight(3).longValueExact()) }

    private fun total(): Quantity =
        db.jdbc.sql("SELECT COALESCE(quantity, 0) FROM product_stock WHERE product_id = :product")
            .param("product", productId).query(BigDecimal::class.java).optional().orElse(BigDecimal.ZERO)
            .let { Quantity.ofScaled(it.movePointRight(3).longValueExact()) }

    private fun statusOf(id: Long): String =
        db.jdbc.sql("SELECT status::text FROM transfers WHERE id = :id").param("id", id).query(String::class.java).single()

    // ── T1: dispatching takes out of the origin and puts into transit ────────

    @Test
    fun `T1 - dispatching takes from the origin, leaves it in transit and does not change the total`() {
        transferId = draft(quantity = 40)
        assertThat(total()).isEqualTo(Quantity.of(100))

        val lines = dispatch()

        assertThat(lines).isEqualTo(1)
        assertThat(stockAt(origin)).isEqualTo(Quantity.of(60))
        assertThat(inTransit()).isEqualTo(Quantity.of(40))
        // The goods on the truck STILL count: otherwise the total would drop during the
        // trip and it would look like a sale.
        assertThat(total()).isEqualTo(Quantity.of(100))
        assertThat(total()).isEqualTo(stockAt(origin) + inTransit())
        assertThat(statusOf(transferId)).isEqualTo("DISPATCHED")
    }

    // ── T2: receiving puts into the destination ──────────────────────────

    @Test
    fun `T2 - receiving puts it in the destination, closes the transit and does not change the total`() {
        transferId = draft(quantity = 40)
        dispatch()

        val lines = receive()

        assertThat(lines).isEqualTo(1)
        assertThat(stockAt(destination)).isEqualTo(Quantity.of(40))
        assertThat(stockAt(origin)).isEqualTo(Quantity.of(60))
        assertThat(inTransit()).isEqualTo(Quantity.ZERO)
        assertThat(total()).isEqualTo(Quantity.of(100))
        assertThat(statusOf(transferId)).isEqualTo("RECEIVED")
    }

    @Test
    fun `the ledger stores the two halves with their type`() {
        transferId = draft(quantity = 40)
        dispatch()
        receive()

        val movimientos = db.jdbc.sql(
            "SELECT move_type::text FROM kardex WHERE move_type IN ('TRANSFER_OUT', 'TRANSFER_IN') ORDER BY id",
        ).query(String::class.java).list()

        assertThat(movimientos).containsExactly("TRANSFER_OUT", "TRANSFER_IN")
    }

    // ── T5: without stock there is no dispatch ──────────────────────────

    @Test
    fun `T5 - without stock nothing is dispatched and nothing is left half done`() {
        transferId = draft(quantity = 500)

        assertThatThrownBy { dispatch() }
            .hasMessageContaining("stock_non_negative")

        // The engine's transaction belongs to the caller; here the state is checked after
        // the exception: no partial outflow, no transit, no status change.
        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(inTransit()).isEqualTo(Quantity.ZERO)
        assertThat(statusOf(transferId)).isEqualTo("DRAFT")
        assertThat(
            db.jdbc.sql("SELECT count(*) FROM kardex WHERE move_type = 'TRANSFER_OUT'").query(Int::class.java).single(),
        ).isZero()
    }

    @Test
    fun `a transfer with two lines moves both or neither`() {
        transferId = draft(quantity = 30)
        db.asOwner.sql(
            """
            INSERT INTO transfer_lines (transfer_id, product_id, quantity)
            VALUES (:transfer, :product, 500)
            """.trimIndent(),
        ).param("transfer", transferId).param("product", productId).update()

        // The second line requests more than there is: the first one does not move either.
        assertThatThrownBy { dispatch() }.hasMessageContaining("stock_non_negative")

        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(inTransit()).isEqualTo(Quantity.ZERO)
        assertThat(statusOf(transferId)).isEqualTo("DRAFT")
    }

    // ── T6: cancelling a dispatched transfer returns the goods ────────────────

    @Test
    fun `T6 - cancelling a dispatched transfer returns the goods to their origin`() {
        transferId = draft(quantity = 40)
        dispatch()

        val lines = cancel()

        assertThat(lines).isEqualTo(1)
        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(inTransit()).isEqualTo(Quantity.ZERO)
        assertThat(total()).isEqualTo(Quantity.of(100))
        assertThat(statusOf(transferId)).isEqualTo("CANCELLED")
    }

    @Test
    fun `cancelling a draft moves nothing`() {
        transferId = draft(quantity = 40)

        assertThat(cancel()).isZero()

        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(statusOf(transferId)).isEqualTo("CANCELLED")
    }

    // ── The invariant ───────────────────────────────────────────────────────

    @Test
    fun `the invariant holds on every transition`() {
        transferId = draft(quantity = 40)

        val inicial = stockAt(origin) + inTransit()
        dispatch()
        val despachado = stockAt(origin) + stockAt(destination) + inTransit()
        receive()
        val recibido = stockAt(origin) + stockAt(destination) + inTransit()

        // Σ stock_by_location + Σ stock_in_transit never changes. It is the invariant
        // that summarizes the whole module.
        assertThat(despachado).isEqualTo(inicial)
        assertThat(recibido).isEqualTo(inicial)
        assertThat(total()).isEqualTo(inicial)
    }

    @Test
    fun `receiving twice does not move the goods twice`() {
        transferId = draft(quantity = 40)
        dispatch()
        receive()

        // The engine has no open transit left to receive: the second round finds no
        // lines. The route rejects it earlier, with `INVALID_TRANSITION`.
        assertThat(receive()).isZero()
        assertThat(stockAt(destination)).isEqualTo(Quantity.of(40))
        assertThat(total()).isEqualTo(Quantity.of(100))
    }
}
