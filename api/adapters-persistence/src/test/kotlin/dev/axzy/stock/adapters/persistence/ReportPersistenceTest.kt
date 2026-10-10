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
 * Los reportes contra PostgreSQL real: el presupuesto de consultas y el **día
 * operativo**.
 *
 * Lo del día operativo se prueba aquí y no por HTTP porque hace falta un movimiento
 * con una hora concreta: por la API se crea «ahora», y una prueba que dependa de la
 * hora a la que se ejecuta es una prueba que falla sola un día.
 */
class ReportPersistenceTest {

    /** La misma zona que usa la API en las pruebas y en Compose. */
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
         * **Sin ningún movimiento de arranque.** Cada prueba inserta las filas del
         * kardex que necesita, con su fecha: un movimiento creado «ahora» cae en el
         * día operativo en curso, y contar con él haría que estas pruebas dependieran
         * de la hora a la que se ejecutan —y de la zona del que las ejecuta.
         */
    }

    /** Un movimiento fechado a mano: es lo que permite probar el día operativo. */
    private fun movementAt(units: Int, at: String) {
        db.asOwner.sql(
            """
            INSERT INTO kardex (product_id, warehouse_id, move_type, units, quantity_after,
                                location_id, to_location_id, moved_at)
            VALUES (:product, 1, 'RECEIPT_IN', :units, 100, :location, :location, :at::timestamptz)
            """.trimIndent(),
        ).param("product", productId).param("units", units).param("location", receivingId).param("at", at).update()
    }

    // ── Presupuesto de consultas ────────────────────────────────────────────

    @Test
    fun `R-P2 - el reporte de existencias son tres sentencias - conteo, totales y pagina`() {
        val filter = InventoryReportFilter(null, null, onlyWithStock = false)

        val statements = db.statements.during { reports.inventory(filter, PageRequest.of()) }
        val totals = db.statements.during { reports.inventoryTotals(filter) }

        assertThat(statements).isEqualTo(2)   // el conteo y la página
        assertThat(totals).isEqualTo(1)       // los totales, sobre el filtro entero
    }

    @Test
    fun `R-P2 - el resumen de movimientos y la reconciliacion son una sentencia`() {
        val summary = db.statements.during {
            reports.movementSummary(MovementSummaryFilter(LocalDate.now(), LocalDate.now(), SummaryGroup.DAY))
        }
        val reconciliation = db.statements.during { reports.divergences() }

        assertThat(summary).isEqualTo(1)
        assertThat(reconciliation).isEqualTo(1)
    }

    @Test
    fun `la exportacion lee por trozos, sin repetir ni perder filas`() {
        repeat(5) { movementAt(units = it + 1, at = "2026-10-05T18:00:00Z") }

        val first = reports.kardexChunk(KardexFilter(null, null, null, null), offset = 0, chunkSize = 2)
        val second = reports.kardexChunk(KardexFilter(null, null, null, null), offset = 2, chunkSize = 2)
        val third = reports.kardexChunk(KardexFilter(null, null, null, null), offset = 4, chunkSize = 2)

        assertThat(first).hasSize(2)
        assertThat(second).hasSize(2)
        assertThat(third).hasSize(1)
        assertThat(first.size + second.size + third.size).isEqualTo(5)
        // Los identificadores de los tres trozos son todos distintos: un `offset` que
        // no avanza repetiría filas y el archivo tendría duplicados.
        val ids = (first + second + third).map { it.id }
        assertThat(ids).doesNotHaveDuplicates()
    }

    // ── El día operativo ────────────────────────────────────────────────────

    @Test
    fun `un movimiento de la noche pertenece a su dia operativo y no al dia UTC`() {
        // 04:00 UTC del 10 son las 22:00 del 9 en Ciudad de México: en el almacén
        // aquello pasó el día 9.
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
    fun `el filtro por fecha incluye el dia operativo entero`() {
        // 05:59 UTC del 10 son las 23:59 del 9; 06:00 UTC del 10 son las 00:00 del 10.
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
    fun `el kardex del inventario usa el mismo dia operativo`() {
        movementAt(units = 3, at = "2026-10-10T04:00:00Z")

        val dia9 = kardexPages.page(
            KardexFilter(null, null, LocalDate.parse("2026-10-09"), LocalDate.parse("2026-10-09")),
            PageRequest.of(),
        )
        val dia10 = kardexPages.page(
            KardexFilter(null, null, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10")),
            PageRequest.of(),
        )

        // El movimiento del 9 operativo aparece el 9 y no el 10, igual que en el resumen.
        assertThat(dia9.totalItems).isEqualTo(1)
        assertThat(dia10.totalItems).isZero()
    }
}
