package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryQueries
import dev.axzy.stock.adapters.persistence.reports.JdbcReportQueries
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.reports.InventoryReportFilter
import dev.axzy.stock.application.reports.MovementSummaryFilter
import dev.axzy.stock.application.reports.SummaryGroup
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The reports against real PostgreSQL: the query budget and the **operating day
 * operativo**.
 *
 * The operating-day part is tested here and not over HTTP because it needs a movement
 * with a specific time: over the API it is created "now", and a test that depends on the
 * time it runs is a test that fails by itself one day.
 */
class ReportPersistenceTest {

    /** The same zone the API uses in the tests and in Compose. */
    private val zone = ZoneId.of("America/Mexico_City")

    private val db = TestPersistence()
    private val kardexPages = JdbcInventoryQueries(db.jdbc, zone)
    private val reports = JdbcReportQueries(db.jdbc, kardexPages, zone)

    private var productId = 0L
    private var receivingId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        productId = db.jdbc.sql(
            "INSERT INTO products (product_code, name, unit_measure_id) VALUES ('REP001', 'Producto', 1) RETURNING id",
        ).query(Long::class.java).single()
        receivingId = db.jdbc.sql("SELECT id FROM locations WHERE kind = 'RECEIVING'").query(Long::class.java).single()
        /*
         * **No startup movement.** Each test inserts the rows of the
         * ledger it needs, with its date: a movement created "now" falls in the
         * current operating day, and counting with it would make these tests depend
         * on the time at which they run —and on the time zone of whoever runs them.
         */
    }

    /** A movement dated by hand: it is what makes it possible to test the operating day. */
    private fun movementAt(units: Int, at: String) {
        db.asOwner.sql(
            """
            INSERT INTO kardex (product_id, warehouse_id, move_type, units, quantity_after,
                                location_id, to_location_id, moved_at)
            VALUES (:product, 1, 'RECEIPT_IN', :units, 100, :location, :location, :at::timestamptz)
            """.trimIndent(),
        ).param("product", productId).param("units", units).param("location", receivingId).param("at", at).update()
    }

    // ── Query budget ────────────────────────────────────────────

    @Test
    fun `R-P2 - the stock report takes three statements - count, totals and page`() {
        val filter = InventoryReportFilter(null, null, onlyWithStock = false)

        val statements = db.statements.during { reports.inventory(filter, PageRequest.of()) }
        val totals = db.statements.during { reports.inventoryTotals(filter) }

        assertThat(statements).isEqualTo(2)   // el conteo y la página
        assertThat(totals).isEqualTo(1)       // los totales, sobre el filtro entero
    }

    @Test
    fun `R-P2 - the movement summary and the reconciliation are a single statement`() {
        val summary = db.statements.during {
            reports.movementSummary(MovementSummaryFilter(LocalDate.now(), LocalDate.now(), SummaryGroup.DAY))
        }
        val reconciliation = db.statements.during { reports.divergences() }

        assertThat(summary).isEqualTo(1)
        assertThat(reconciliation).isEqualTo(1)
    }

    @Test
    fun `the export reads in chunks, without repeating or losing rows`() {
        repeat(5) { movementAt(units = it + 1, at = "2026-10-05T18:00:00Z") }

        val first = reports.kardexChunk(KardexFilter(null, null, null, null), offset = 0, chunkSize = 2)
        val second = reports.kardexChunk(KardexFilter(null, null, null, null), offset = 2, chunkSize = 2)
        val third = reports.kardexChunk(KardexFilter(null, null, null, null), offset = 4, chunkSize = 2)

        assertThat(first).hasSize(2)
        assertThat(second).hasSize(2)
        assertThat(third).hasSize(1)
        assertThat(first.size + second.size + third.size).isEqualTo(5)
        // The identifiers of the three chunks are all different: an `offset` that
        // does not advance would repeat rows and the file would have duplicates.
        val ids = (first + second + third).map { it.id }
        assertThat(ids).doesNotHaveDuplicates()
    }

    // ── The operating day ────────────────────────────────────────────────────

    @Test
    fun `a night movement belongs to its operating day and not to the UTC day`() {
        // 04:00 UTC on the 10th is 22:00 on the 9th in Mexico City: in the warehouse
        // that happened on the 9th.
        movementAt(units = 7, at = "2026-10-10T04:00:00Z")

        val dia9 = reports.movementSummary(
            MovementSummaryFilter(LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-09"), SummaryGroup.DAY),
        )
        val dia10 = reports.movementSummary(
            MovementSummaryFilter(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"), SummaryGroup.DAY),
        )

        assertThat(dia9.single().key).isEqualTo("2026-10-09")
        assertThat(dia9.single().inUnits).isEqualTo(Quantity.of(7))
        assertThat(dia10).isEmpty()
    }

    @Test
    fun `the date filter includes the whole operating day`() {
        // 05:59 UTC on the 10th is 23:59 on the 9th; 06:00 UTC on the 10th is 00:00 on the 10th.
        movementAt(units = 1, at = "2026-10-10T05:59:00Z")
        movementAt(units = 2, at = "2026-10-10T06:00:00Z")

        val dia9 = reports.movementSummary(
            MovementSummaryFilter(LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-09"), SummaryGroup.DAY),
        )
        val dia10 = reports.movementSummary(
            MovementSummaryFilter(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"), SummaryGroup.DAY),
        )

        assertThat(dia9.single().inUnits).isEqualTo(Quantity.of(1))
        assertThat(dia10.single().inUnits).isEqualTo(Quantity.of(2))
    }

    @Test
    fun `the inventory ledger uses the same operating day`() {
        movementAt(units = 3, at = "2026-10-10T04:00:00Z")

        val dia9 = kardexPages.page(
            KardexFilter(null, null, LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-09")),
            PageRequest.of(),
        )
        val dia10 = kardexPages.page(
            KardexFilter(null, null, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")),
            PageRequest.of(),
        )

        // The movement of operating day the 9th appears on the 9th and not the 10th, as in the summary.
        assertThat(dia9.totalItems).isEqualTo(1)
        assertThat(dia10.totalItems).isZero()
    }
}
