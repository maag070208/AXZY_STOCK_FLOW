package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.fulfillment.JdbcOrders
import dev.axzy.stock.application.fulfillment.OrderFilter
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Surtido contra PostgreSQL real: el presupuesto de consultas y la **confirmación
 * simultánea** (S3).
 *
 * La prueba de S3 que importa es esta y no la de HTTP: el reclamo de la confirmación
 * es una sentencia condicional, y lo que hay que demostrar es que dos transacciones de
 * verdad no se la llevan las dos. La de HTTP comprueba lo mismo en secuencia.
 */
class FulfillmentPersistenceTest {

    private val db = TestPersistence()
    private val orders = JdbcOrders(db.jdbc)

    private var orderId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        orderId = db.asOwner.sql(
            "INSERT INTO orders (order_number, status) VALUES ('ORD-2026-0001', 'PACKAGED') RETURNING id",
        ).query(Long::class.java).single()
    }

    @Test
    fun `R-P2 - the order list takes two statements`() {
        val statements = db.statements.during {
            orders.page(OrderFilter(null, null), PageRequest.of())
        }
        val page = orders.page(OrderFilter(null, null), PageRequest.of())

        assertThat(statements).isEqualTo(2)   // el conteo y la página
        assertThat(page.totalItems).isEqualTo(1)
    }

    @Test
    fun `S3 - two simultaneous confirmations claim the order only once`() {
        val firstClaimed = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Int> {
                db.tx.inTransaction {
                    val claimed = orders.claimConfirmation(orderId, Instant.now())
                    // Se mantiene la transacción abierta: la segunda tiene que esperar
                    // al bloqueo de la fila y, al continuar, reevaluar la condición.
                    firstClaimed.countDown()
                    Thread.sleep(400)
                    claimed.asClaimed()
                }.getOrNull() ?: -1
            }
            val second = executor.submit<Int> {
                firstClaimed.await()
                db.tx.inTransaction {
                    orders.claimConfirmation(orderId, Instant.now()).asClaimed()
                }.getOrNull() ?: -1
            }

            val claims = listOf(first.get(), second.get())
            assertThat(claims).containsExactlyInAnyOrder(1, 0)
        } finally {
            executor.shutdownNow()
        }

        // Y la orden quedó confirmada una sola vez.
        assertThat(orders.detail(orderId)!!.header.status).isEqualTo("PENDING_TO_OUT")
    }
}

private fun Int.asClaimed(): dev.axzy.stock.domain.common.Either<Nothing, Int> =
    dev.axzy.stock.domain.common.Either.Right(this)
