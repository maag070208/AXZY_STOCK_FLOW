package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * El motor de inventario bajo concurrencia, contra PostgreSQL real y con el usuario de la API.
 * Son las pruebas A3 y A4 de docs/flujos/existencias-y-ajustes.md (requisitos Q1 y Q7).
 */
class InventoryEngineConcurrencyTest {

    private val db = TestPersistence()
    private var productId = 0L
    private var shelfA = 0L
    private var shelfB = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        productId = db.jdbc.sql("INSERT INTO products (product_code, name, unit_measure_id) VALUES ('CONC1', 'Concurrente', 1) RETURNING id")
            .query(Long::class.java).single()
        shelfA = shelf("A")
        shelfB = shelf("B")
        // 100 unidades en cada estante: total 200.
        move(shelfA, 100)
        move(shelfB, 100)
        recalculate()
    }

    private fun shelf(code: String): Long =
        db.jdbc.sql("INSERT INTO locations (warehouse_id, hall, code, position, levels, kind) VALUES (1, '05', :code, 'A', 1, 'SHELF') RETURNING id")
            .param("code", code).query(Long::class.java).single()

    private fun move(locationId: Long, delta: Int) {
        db.jdbc.sql("SELECT apply_location_movement(:product, :location, 1, :delta, FALSE)")
            .param("product", productId).param("location", locationId).param("delta", delta)
            .query(java.math.BigDecimal::class.java).single()
    }

    private fun recalculate() {
        db.jdbc.sql("SELECT recalculate_product_total(:product)").param("product", productId)
            .query(java.math.BigDecimal::class.java).single()
    }

    private fun total(): String = db.jdbc.sql("SELECT quantity::text FROM product_stock WHERE product_id = :id")
        .param("id", productId).query(String::class.java).single()

    private fun sumOfLocations(): String = db.jdbc.sql("SELECT sum(quantity)::text FROM stock_by_location WHERE product_id = :id")
        .param("id", productId).query(String::class.java).single()

    @Test
    fun `A4 - dos movimientos simultaneos del mismo producto no pierden el total`() {
        val firstRecalculated = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            // La primera transacción deja su recálculo sin confirmar mientras la segunda hace el suyo.
            val first = executor.submit {
                db.tx.inTransaction {
                    move(shelfA, 10)
                    recalculate()
                    firstRecalculated.countDown()
                    Thread.sleep(500)
                    Unit.asRight()
                }
            }
            val second = executor.submit {
                firstRecalculated.await(5, TimeUnit.SECONDS)
                db.tx.inTransaction {
                    move(shelfB, 5)
                    recalculate()
                    Unit.asRight()
                }
            }
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        // Sin el bloqueo de V10 el total quedaba en 205.000: el segundo recálculo no veía los 10 del primero.
        assertThat(sumOfLocations()).isEqualTo("215.000")
        assertThat(total()).isEqualTo("215.000")
        assertThat(db.jdbc.sql("SELECT count(*) FROM check_projections()").query(Int::class.java).single()).isZero()
    }

    @Test
    fun `A3 - dos salidas simultaneas no dejan el saldo negativo - una entra y la otra se rechaza`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = try {
            // Cada una saca 60 de un estante con 100: las dos no caben.
            (1..2).map {
                executor.submit<Boolean> {
                    start.await(5, TimeUnit.SECONDS)
                    runCatching {
                        db.tx.inTransaction {
                            move(shelfA, -60)
                            Thread.sleep(200)
                            recalculate()
                            Unit.asRight()
                        }
                    }.isSuccess
                }
            }.also { start.countDown() }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false)
        assertThat(db.jdbc.sql("SELECT quantity::text FROM stock_by_location WHERE location_id = :id").param("id", shelfA).query(String::class.java).single())
            .isEqualTo("40.000")
        assertThat(total()).isEqualTo("140.000")
    }

    @Test
    fun `sacar mas de lo que hay lo rechaza la base`() {
        // Lo rechaza el CHECK de la tabla, dentro del propio UPDATE. El mensaje de «stock
        // insuficiente» que la función de V6 prometía no llega a ejecutarse en este camino: quien
        // le dice al usuario cuánto había es el caso de uso, que lee el saldo bloqueado antes.
        assertThatThrownBy { move(shelfA, -150) }
            .rootCause().hasMessageContaining("stock_non_negative")
        assertThat(sumOfLocations()).isEqualTo("200.000")
    }

    @Test
    fun `A9 - un movimiento del kardex no se puede modificar ni borrar, ni siquiera como dueno`() {
        db.jdbc.sql("SELECT record_movement(:product, 1, 'INITIAL', 10, NULL, :shelf, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
            .param("product", productId).param("shelf", shelfA).query(Long::class.java).single()

        assertThatThrownBy { db.asOwner.sql("UPDATE kardex SET units = 999").update() }.rootCause().isNotNull()
        assertThatThrownBy { db.asOwner.sql("DELETE FROM kardex").update() }.rootCause().isNotNull()
        assertThat(db.jdbc.sql("SELECT units::text FROM kardex").query(String::class.java).single()).isEqualTo("10.000")
    }
}
