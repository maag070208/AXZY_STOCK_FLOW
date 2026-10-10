package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.labeling.JdbcLabels
import dev.axzy.stock.adapters.persistence.transfer.JdbcTransfers
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.transfer.TransferFilter
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * El presupuesto de consultas de traslados y etiquetas (M05 y M06, listón §4).
 *
 * Faltaba: estos dos módulos tenían sus pruebas de flujo y su motor probado, pero nadie había
 * **medido** cuántas sentencias cuesta leerlos, y un presupuesto sin medir se pierde en el primer
 * cambio bienintencionado. Lo que se fija aquí es que un listado son dos sentencias —el conteo y
 * la página— y que las líneas de un traslado vienen en su propia consulta y no una por línea.
 */
class TransferAndLabelBudgetTest {

    private val db = TestPersistence()
    private val transfers = JdbcTransfers(db.jdbc)
    private val labels = JdbcLabels(db.jdbc, db.json)

    private var transferId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        // Un traslado exige bodegas **distintas** (lo dice un CHECK), así que hay dos.
        val origen = db.asOwner.sql("SELECT id FROM warehouses ORDER BY id LIMIT 1").query(Long::class.java).single()
        val destino = db.asOwner.sql(
            "INSERT INTO warehouses (code, name) VALUES ('NORTE', 'Bodega Norte') RETURNING id",
        ).query(Long::class.java).single()
        transferId = db.asOwner.sql(
            """
            INSERT INTO transfers (code, from_warehouse_id, to_warehouse_id, status)
            VALUES ('TR-2026-0001', :origen, :destino, 'DRAFT') RETURNING id
            """.trimIndent(),
        ).param("origen", origen).param("destino", destino).query(Long::class.java).single()
        val template = db.asOwner.sql(
            "INSERT INTO label_templates (code, name, zpl) " +
                "VALUES ('ETI-01', 'Etiqueta de estante', '^XA^FDHOLA^FS^XZ') RETURNING id",
        ).query(Long::class.java).single()
        // Tres trabajos: la página tiene que costar lo mismo con uno que con veinte.
        repeat(3) {
            db.asOwner.sql(
                "INSERT INTO print_jobs (label_template_id, payload, status) " +
                    "VALUES (:template, '{}'::jsonb, 'PENDING')",
            ).param("template", template).update()
        }
    }

    @Test
    fun `listing transfers is two statements and the detail another two`() {
        val listado = db.statements.during { transfers.page(TransferFilter(null, null), PageRequest.of()) }
        val detalle = db.statements.during { transfers.detail(transferId) }

        assertThat(listado).isEqualTo(2)
        // La cabecera y sus líneas.
        assertThat(detalle).isEqualTo(2)
    }

    @Test
    fun `listing print jobs is two statements`() {
        val listado = db.statements.during { labels.page(null, PageRequest.of()) }
        val trabajos = labels.page(null, PageRequest.of())

        assertThat(listado).isEqualTo(2)
        assertThat(trabajos.items).hasSize(3)
        assertThat(trabajos.totalItems).isEqualTo(3)
    }
}
