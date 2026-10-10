package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.catalog.JdbcLocations
import dev.axzy.stock.adapters.persistence.catalog.JdbcProducts
import dev.axzy.stock.adapters.persistence.catalog.JdbcWarehouses
import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryCatalog
import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryQueries
import dev.axzy.stock.adapters.persistence.inventory.JdbcStockLedger
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.LocationRef
import dev.axzy.stock.application.inventory.MovementCommand
import dev.axzy.stock.application.inventory.StockFilter
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.util.UUID

/**
 * Existencias contra PostgreSQL real: el adaptador que llama al motor y las dos
 * consultas paginadas.
 *
 * La concurrencia del motor (A3, A4) y la inmutabilidad del kardex (A9) ya están en
 * `InventoryEngineConcurrencyTest`, con dos transacciones de verdad.
 */
class InventoryPersistenceTest {

    private val db = TestPersistence()
    private val ledger = JdbcStockLedger(db.jdbc)
    private val queries = JdbcInventoryQueries(db.jdbc, ZoneId.of("America/Mexico_City"))
    private val catalog = JdbcInventoryCatalog(
        JdbcProducts(db.jdbc, db.json),
        JdbcLocations(db.jdbc),
        JdbcWarehouses(db.jdbc),
    )

    private var productId = 0L
    private var shelfId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        productId = db.jdbc.sql(
            "INSERT INTO products (product_code, name, unit_measure_id) VALUES ('INV001', 'Producto', 1) RETURNING id",
        ).query(Long::class.java).single()
        shelfId = db.jdbc.sql(
            "INSERT INTO locations (warehouse_id, hall, code, position, levels, kind) " +
                "VALUES (1, '04', 'B', 'C', 1, 'SHELF') RETURNING id",
        ).query(Long::class.java).single()
        // 100 unidades en el estante, por el motor.
        ledger.apply(
            MovementCommand(
                productId = productId,
                warehouseId = 1,
                moveType = MovementType.INITIAL,
                units = Quantity.of(100),
                toLocationId = shelfId,
            ),
        )
    }

    @Test
    fun `R-P2 - the stock list takes two statements`() {
        val statements = db.statements.during {
            queries.page(StockFilter(null, null, onlyWithStock = false), PageRequest.of())
        }
        val page = queries.page(StockFilter(null, null, onlyWithStock = false), PageRequest.of())

        assertThat(statements).isEqualTo(2)   // el conteo y la página
        assertThat(page.totalItems).isEqualTo(1)
        assertThat(page.items.single().quantity).isEqualTo(Quantity.of(100))
    }

    @Test
    fun `R-P2 - the ledger takes two statements`() {
        val statements = db.statements.during {
            queries.page(KardexFilter(null, null, null, null), PageRequest.of())
        }

        assertThat(statements).isEqualTo(2)
    }

    @Test
    fun `the stock count uses the same filters as the list`() {
        val other = db.jdbc.sql(
            "INSERT INTO products (product_code, name, unit_measure_id) VALUES ('OTRO01', 'Otro', 1) RETURNING id",
        ).query(Long::class.java).single()
        db.jdbc.sql(
            "INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity) " +
                "VALUES (:product, :location, 1, 5)",
        ).param("product", other).param("location", shelfId).update()

        val page = queries.page(StockFilter(ProductCode.of("INV001"), null, onlyWithStock = false), PageRequest.of())

        assertThat(page.totalItems).isEqualTo(1)
        assertThat(page.items.single().productCode).isEqualTo("INV001")
    }

    @Test
    fun `the balance is read locked inside the transaction and the total comes from the projection`() {
        // El `FOR UPDATE` solo tiene efecto dentro de una transacción. Que el saldo
        // bloqueado impida el negativo se demuestra en `InventoryEngineConcurrencyTest`
        // (A3), con dos transacciones de verdad.
        val available = db.tx.inTransaction { ledger.balanceAt(productId, shelfId, forUpdate = true).asRight() }

        assertThat(available.getOrNull()).isEqualTo(Quantity.of(100))
        // La proyección la mantuvo el motor al aplicar el movimiento, no una suma aquí.
        assertThat(ledger.productTotal(productId)).isEqualTo(Quantity.of(100))
    }

    @Test
    fun `a balance without a row is zero, not null`() {
        assertThat(ledger.balanceAt(productId, 9_999L, forUpdate = false)).isEqualTo(Quantity.ZERO)
    }

    @Test
    fun `the idempotency key returns the same movement and does not write twice`() {
        val key = UUID.randomUUID()
        // Un ajuste **siempre** lleva motivo: lo impone un `CHECK` del esquema y lo
        // exige el flujo. Un `ADJUSTMENT_IN` sin motivo no llega a la tabla.
        val command = MovementCommand(
            productId = productId,
            warehouseId = 1,
            moveType = MovementType.ADJUSTMENT_IN,
            units = Quantity.of(10),
            toLocationId = shelfId,
            adjustmentReason = AdjustmentReason.COUNT_ERROR,
            adjustmentOrigin = AdjustmentOrigin.MANUAL_COUNT,
            clientUuid = key,
        )

        val first = ledger.apply(command)
        val second = ledger.apply(command)

        assertThat(second).isEqualTo(first)
        assertThat(ledger.movementIdOf(key)).isEqualTo(first)
        assertThat(db.jdbc.sql("SELECT count(*) FROM kardex WHERE client_uuid = :key").param("key", key).query(Int::class.java).single())
            .isEqualTo(1)
        assertThat(ledger.productTotal(productId)).isEqualTo(Quantity.of(110))
    }

    @Test
    fun `the ledger stores the balance after and the visible names`() {
        // Mercancía en la recepción, que es de donde sale un ubicar.
        val receivingId = db.jdbc.sql("SELECT id FROM locations WHERE kind = 'RECEIVING'").query(Long::class.java).single()
        ledger.apply(
            MovementCommand(
                productId = productId, warehouseId = 1, moveType = MovementType.INITIAL,
                units = Quantity.of(100), toLocationId = receivingId,
            ),
        )

        val kardexId = ledger.apply(
            MovementCommand(
                productId = productId,
                warehouseId = 1,
                moveType = MovementType.LOCATE,
                units = Quantity.of(30),
                fromLocationId = receivingId,
                toLocationId = shelfId,
            ),
        )

        val line = queries.page(KardexFilter(null, MovementType.LOCATE, null, null), PageRequest.of()).items.single()

        assertThat(line.id).isEqualTo(kardexId)
        // `quantity_after` es el total del PRODUCTO, no el saldo de la ubicación: tras
        // ubicar sigue siendo 200 (100 en el estante y 100 en la recepción). Eso es lo
        // que hace el ledger auto-auditable y lo que demuestra que ubicar no cambia el
        // total (A1).
        assertThat(line.quantityAfter).isEqualTo(Quantity.of(200))
        assertThat(line.fromLocationDisplayName).isEqualTo("RECEIVING")
        assertThat(line.toLocationDisplayName).isEqualTo("04-B-C")
    }

    @Test
    fun `the inventory catalog resolves the three forms of visible name`() {
        assertThat(catalog.activeProduct(ProductCode.of("INV001"))!!.id).isEqualTo(productId)
        assertThat(catalog.activeLocation(1, LocationRef.Shelf("04", "B", "C"))!!.id).isEqualTo(shelfId)
        assertThat(catalog.activeLocation(1, LocationRef.Receiving)!!.kind.name).isEqualTo("RECEIVING")
        assertThat(catalog.activeLocation(1, LocationRef.Unassigned)!!.kind.name).isEqualTo("UNASSIGNED")
        // Y una que no existe no se inventa.
        assertThat(catalog.activeLocation(1, LocationRef.Shelf("99", "Z", "Z"))).isNull()
    }

    @Test
    fun `an inactive product or location is not found`() {
        db.jdbc.sql("UPDATE products SET active = FALSE WHERE id = :id").param("id", productId).update()
        db.jdbc.sql("UPDATE locations SET active = FALSE WHERE id = :id").param("id", shelfId).update()

        assertThat(catalog.activeProduct(ProductCode.of("INV001"))).isNull()
        assertThat(catalog.activeLocation(1, LocationRef.Shelf("04", "B", "C"))).isNull()
    }

    @Test
    fun `the default warehouse exists and is the seeded one`() {
        assertThat(catalog.defaultWarehouseId()).isEqualTo(1L)
    }
}
