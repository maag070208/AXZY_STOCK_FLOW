package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.receiving.JdbcInvoices
import dev.axzy.stock.application.receiving.InvoiceFilter
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.identity.UserAdminStore
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Receiving's concurrency against real PostgreSQL (M03).
 *
 * What is tested here and not over HTTP: counting puts goods into `RECEIVING` by the **difference**
 * against what was already entered, so two simultaneous counts of the same line that read the same
 * balance would enter the same difference twice and **the stock would be doubled**. The claim is
 * a conditional statement on `received_quantity`, and what has to be shown is that two real
 * transactions do not both get it.
 */
class ReceivingConcurrencyTest {

    private val db = TestPersistence()
    private val invoices = JdbcInvoices(db.jdbc)

    private var lineId = 0L
    private var capturista = 0L
    private var invoiceId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        val productId = db.asOwner.sql(
            "INSERT INTO products (product_code, name, unit_measure_id) " +
                "SELECT 'ABRAA1B', 'ABRAZADERA', id FROM units_of_measure WHERE code = 'PZA' RETURNING id",
        ).query(Long::class.java).single()
        invoiceId = db.asOwner.sql(
            "INSERT INTO invoices (invoice_number, receipt_date, invoice_type, status) " +
                "VALUES ('F-2026-001', CURRENT_DATE, 'NATIONAL', 'PENDING') RETURNING id",
        ).query(Long::class.java).single()
        // The counter must exist: `captured_by` is a foreign key.
        capturista = db.asOwner.sql(
            "INSERT INTO users (username, password_hash, name) VALUES ('capturista', 'hash', 'Capturista') RETURNING id",
        ).query(Long::class.java).single()
        lineId = db.asOwner.sql(
            """
            INSERT INTO invoice_lines (invoice_id, product_id, container, unit_measure_id, quantity, box_quantity, units_per_box, status)
            SELECT :invoice, :product, 'CONT-01', id, 10, 2, 5, 'PENDING' FROM units_of_measure WHERE code = 'PZA'
            RETURNING id
            """.trimIndent(),
        ).param("invoice", invoiceId).param("product", productId).query(Long::class.java).single()
    }

    private fun line(received: String, counted: String, status: InvoiceLineStatus = InvoiceLineStatus.CAPTURED): InvoiceLine =
        InvoiceLine(
            id = lineId,
            productCode = ProductCode.of("ABRAA1B"),
            invoicedQuantity = Quantity.of(10),
            unitsPerBox = UnitsPerBox.of(5),
            status = status,
            physicalQuantity = Quantity.of(counted.toLong()),
            physicalBoxQuantity = BoxQuantity.of(2),
            receivedQuantity = Quantity.of(received.toLong()),
            capturedBy = capturista,
        )

    // ── Query budget (§4 bar) ────────────────────────────────

    @Test
    fun `listing invoices is two statements and the detail is two`() {
        val listado = db.statements.during { invoices.page(InvoiceFilter(null, null), PageRequest.of()) }
        val detalle = db.statements.during { invoices.detail(invoiceId) }

        // The count and the page.
        assertThat(listado).isEqualTo(2)
        // The header and the lines with their joins: twenty lines are not twenty queries.
        assertThat(detalle).isEqualTo(2)
    }

    @Test
    fun `two simultaneous captures only claim the line once`() {
        val primeraReclamo = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val primera = executor.submit<Int> {
                db.tx.inTransaction {
                    val changed = invoices.claimCapture(line(received = "9", counted = "9"), Quantity.of(0), Instant.now())
                    // The transaction stays open: the second one has to wait for the row lock
                    // and, when it continues, re-evaluate the condition.
                    primeraReclamo.countDown()
                    Thread.sleep(400)
                    changed.asRight()
                }.getOrNull() ?: -1
            }
            val segunda = executor.submit<Int> {
                primeraReclamo.await()
                db.tx.inTransaction {
                    invoices.claimCapture(line(received = "9", counted = "9"), Quantity.of(0), Instant.now()).asRight()
                }.getOrNull() ?: -1
            }

            assertThat(primera.get()).isEqualTo(1)
            // Zero: the line already changed. Without this condition, both would enter 9 and the
            // stock would have 18 of goods that were counted once.
            assertThat(segunda.get()).isZero()
            assertThat(
                db.asOwner.sql("SELECT received_quantity FROM invoice_lines WHERE id = :id")
                    .param("id", lineId).query(java.math.BigDecimal::class.java).single(),
            ).isEqualByComparingTo("9")
        } finally {
            executor.shutdownNow()
        }
    }
}
