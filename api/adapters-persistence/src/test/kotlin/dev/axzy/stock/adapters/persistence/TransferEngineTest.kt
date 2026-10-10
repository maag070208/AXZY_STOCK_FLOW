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
 * El motor de traslados (V13) contra PostgreSQL real.
 *
 * Aquí se demuestran las pruebas de aceptación del flujo que son **del motor**, no de
 * la ruta: T1, T2, T5 y T6, más la invariante que las resume todas —
 * `Σ stock_by_location + Σ stock_in_transit` se conserva en cada transición—. Las de
 * la API (T3, T4, T7, T8) van con las rutas.
 *
 * El motor vive en SQL porque un traslado mueve saldo, y el saldo lo mueve el motor:
 * un `UPDATE stock_by_location` desde Kotlin sería la segunda ruta de escritura que
 * `InventoryWriteGuardTest` prohíbe.
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
        // El kardex guarda quién movió: sin usuario, la clave foránea lo rechaza.
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

        // 100 unidades en la recepción del origen, por el motor.
        db.jdbc.sql(
            """
            SELECT record_movement(:product, 1, 'INITIAL', 100, NULL, :location, NULL, NULL, NULL, NULL, :user, NULL, NULL)
            """.trimIndent(),
        ).param("product", productId).param("location", origin).param("user", userId).query(Long::class.java).single()
    }

    /** Un traslado en borrador con una línea de la cantidad pedida. */
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

    // ── T1: despachar saca del origen y pone en tránsito ────────────────────

    @Test
    fun `T1 - despachar saca del origen, deja en transito y no cambia el total`() {
        transferId = draft(quantity = 40)
        assertThat(total()).isEqualTo(Quantity.of(100))

        val lines = dispatch()

        assertThat(lines).isEqualTo(1)
        assertThat(stockAt(origin)).isEqualTo(Quantity.of(60))
        assertThat(inTransit()).isEqualTo(Quantity.of(40))
        // La mercancía en el camión SIGUE contando: si no, el total bajaría durante el
        // viaje y parecería una venta.
        assertThat(total()).isEqualTo(Quantity.of(100))
        assertThat(total()).isEqualTo(stockAt(origin) + inTransit())
        assertThat(statusOf(transferId)).isEqualTo("DISPATCHED")
    }

    // ── T2: recibir pone en el destino ──────────────────────────────────────

    @Test
    fun `T2 - recibir pone en el destino, cierra el transito y no cambia el total`() {
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
    fun `el kardex guarda las dos mitades con su tipo`() {
        transferId = draft(quantity = 40)
        dispatch()
        receive()

        val movimientos = db.jdbc.sql(
            "SELECT move_type::text FROM kardex WHERE move_type IN ('TRANSFER_OUT', 'TRANSFER_IN') ORDER BY id",
        ).query(String::class.java).list()

        assertThat(movimientos).containsExactly("TRANSFER_OUT", "TRANSFER_IN")
    }

    // ── T5: sin existencias no se despacha ──────────────────────────────────

    @Test
    fun `T5 - sin existencias no se despacha y nada queda a medias`() {
        transferId = draft(quantity = 500)

        assertThatThrownBy { dispatch() }
            .hasMessageContaining("stock_non_negative")

        // La transacción del motor es de quien llama; aquí se comprueba el estado tras
        // la excepción: ni salida parcial, ni tránsito, ni cambio de estado.
        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(inTransit()).isEqualTo(Quantity.ZERO)
        assertThat(statusOf(transferId)).isEqualTo("DRAFT")
        assertThat(
            db.jdbc.sql("SELECT count(*) FROM kardex WHERE move_type = 'TRANSFER_OUT'").query(Int::class.java).single(),
        ).isZero()
    }

    @Test
    fun `un traslado con dos lineas mueve las dos o ninguna`() {
        transferId = draft(quantity = 30)
        db.asOwner.sql(
            """
            INSERT INTO transfer_lines (transfer_id, product_id, quantity)
            VALUES (:transfer, :product, 500)
            """.trimIndent(),
        ).param("transfer", transferId).param("product", productId).update()

        // La segunda línea pide más de lo que hay: la primera tampoco se mueve.
        assertThatThrownBy { dispatch() }.hasMessageContaining("stock_non_negative")

        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(inTransit()).isEqualTo(Quantity.ZERO)
        assertThat(statusOf(transferId)).isEqualTo("DRAFT")
    }

    // ── T6: cancelar un despachado devuelve la mercancía ────────────────────

    @Test
    fun `T6 - cancelar un despachado devuelve la mercancia a su origen`() {
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
    fun `cancelar un borrador no mueve nada`() {
        transferId = draft(quantity = 40)

        assertThat(cancel()).isZero()

        assertThat(stockAt(origin)).isEqualTo(Quantity.of(100))
        assertThat(statusOf(transferId)).isEqualTo("CANCELLED")
    }

    // ── La invariante ───────────────────────────────────────────────────────

    @Test
    fun `la invariante se conserva en cada transicion`() {
        transferId = draft(quantity = 40)

        val inicial = stockAt(origin) + inTransit()
        dispatch()
        val despachado = stockAt(origin) + stockAt(destination) + inTransit()
        receive()
        val recibido = stockAt(origin) + stockAt(destination) + inTransit()

        // Σ stock_by_location + Σ stock_in_transit no cambia nunca. Es la invariante
        // que resume el módulo entero.
        assertThat(despachado).isEqualTo(inicial)
        assertThat(recibido).isEqualTo(inicial)
        assertThat(total()).isEqualTo(inicial)
    }

    @Test
    fun `recibir dos veces no mueve la mercancia dos veces`() {
        transferId = draft(quantity = 40)
        dispatch()
        receive()

        // Al motor no le queda tránsito abierto que recibir: la segunda vuelta no
        // encuentra líneas. La ruta lo rechaza antes, con `INVALID_TRANSITION`.
        assertThat(receive()).isZero()
        assertThat(stockAt(destination)).isEqualTo(Quantity.of(40))
        assertThat(total()).isEqualTo(Quantity.of(100))
    }
}
