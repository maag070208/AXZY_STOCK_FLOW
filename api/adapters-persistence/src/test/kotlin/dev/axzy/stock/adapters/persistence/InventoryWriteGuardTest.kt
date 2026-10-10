package dev.axzy.stock.adapters.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The plan's rule: **the balance is changed by the database, with a lock, and only by the engine**
 * (requirements Q7 and R-P6, and the risk "two write paths to the inventory" of
 * PLAN-IMPLEMENTACION §6).
 *
 * No adapter layer may write `stock_by_location` by hand,
 * `product_stock` or `kardex`: `record_movement` moves all three, inside
 * its transaction and with the lock that avoids losing writes. A stray `UPDATE`
 * from the API would compile, pass the happy-path tests and break
 * concurrency exactly when the warehouse is under load.
 *
 * **Why it looks at the source code and not the database.** It is a rule about how the
 * adapter is written, and in SQL there is no way to tell a legitimate `SELECT` from an
 * `UPDATE` by querying the database catalog. The layer's `.kt` files are reviewed:
 * the list is short, the rule is clear, and the failure names the file and
 * the line.
 */
class InventoryWriteGuardTest {

    /** The three tables that only grow or that only the engine moves. */
    private val protectedTables = listOf("stock_by_location", "product_stock", "kardex", "audit_log")

    private val persistenceRoot = File("src/main/kotlin/dev/axzy/stock/adapters/persistence")

    @Test
    fun `no adapter writes by hand the tables the engine moves`() {
        val offenders = mutableListOf<String>()

        persistenceRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                // The CODE is analyzed, not the prose: the lines of a block
                // comment (`/* … */`, which start with `*`) talk about writes
                // without being writes, and the first version of this guard accused a
                // comment that explained exactly what it forbids.
                val trimmed = line.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//").lowercase()
                val writes = code.contains("update ") || code.contains("delete from ") || code.contains("insert into ")
                val touchesProtected = protectedTables.any { code.contains(it) }
                // The ledger IS inserted into, but only from the engine: `record_movement`
                // is a database function, and this adapter calls it with a SELECT.
                if (writes && touchesProtected) {
                    offenders += "${file.path}:${index + 1} → ${line.trim()}"
                }
            }
        }

        assertThat(offenders)
            .describedAs("una escritura directa al inventario tiene que salir del motor SQL, no de la API")
            .isEmpty()
    }

    @Test
    fun `the only one that applies movements is the ledger, and it does so through the engine function`() {
        val ledger = File(persistenceRoot, "inventory/JdbcStockLedger.kt").readText().lowercase()

        assertThat(ledger).contains("record_movement")
        // No `UPDATE` or `INSERT` of its own: the engine does them inside its transaction.
        assertThat(ledger).doesNotContain("update stock_by_location")
        assertThat(ledger).doesNotContain("insert into stock_by_location")
        assertThat(ledger).doesNotContain("insert into kardex")
    }
}
