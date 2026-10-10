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
 * La concurrencia de la recepción contra PostgreSQL real (M03).
 *
 * Lo que se prueba aquí y no por HTTP: contar mete mercancía en `RECEIVING` por la **diferencia**
 * contra lo ya ingresado, así que dos capturas simultáneas de la misma línea que lean el mismo
 * saldo ingresarían la misma diferencia dos veces y **el stock se duplicaría**. La reclamación es
 * una sentencia condicional sobre `received_quantity`, y lo que hay que demostrar es que dos
 * transacciones de verdad no se la llevan las dos.
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
        // El capturista tiene que existir: `captured_by` es una clave foránea.
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

    // ── Presupuesto de consultas (listón §4) ────────────────────────────────

    @Test
    fun `listar facturas son dos sentencias y el detalle son dos`() {
        val listado = db.statements.during { invoices.page(InvoiceFilter(null, null), PageRequest.of()) }
        val detalle = db.statements.during { invoices.detail(invoiceId) }

        // El conteo y la página.
        assertThat(listado).isEqualTo(2)
        // La cabecera y las líneas con sus uniones: veinte líneas no son veinte consultas.
        assertThat(detalle).isEqualTo(2)
    }

    @Test
    fun `dos capturas simultaneas solo reclaman la linea una vez`() {
        val primeraReclamo = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val primera = executor.submit<Int> {
                db.tx.inTransaction {
                    val changed = invoices.claimCapture(line(received = "9", counted = "9"), Quantity.of(0), Instant.now())
                    // Se mantiene la transacción abierta: la segunda tiene que esperar al bloqueo
                    // de la fila y, al continuar, reevaluar la condición.
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
            // Cero: la línea ya cambió. Sin esta condición, las dos ingresarían 9 y el stock
            // tendría 18 de una mercancía que se contó una vez.
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
