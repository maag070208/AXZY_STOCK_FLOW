package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Lo que el usuario de la API NO puede hacer en la base (migración V11). Si un defecto de la API
 * intentara cualquiera de estas cosas, la base lo rechaza: no depende de que el código se porte bien.
 */
class ApiRolePrivilegesTest {

    private val db = TestPersistence()

    /** Spring envuelve el error del controlador: lo que dijo PostgreSQL está en la causa raíz. */
    private fun assertRejected(reason: String, operation: () -> Unit) {
        assertThatThrownBy { operation() }.rootCause().hasMessageContaining(reason)
    }

    @BeforeEach
    fun clean() = TestDatabase.reset()

    @Test
    fun `cannot create, alter or delete tables`() {
        assertRejected("permission denied for schema public") { db.jdbc.sql("CREATE TABLE intruso (x int)").update() }
        assertRejected("must be owner of table products") { db.jdbc.sql("ALTER TABLE products ADD COLUMN intruso int").update() }
        assertRejected("must be owner of table products") { db.jdbc.sql("DROP TABLE products").update() }
    }

    @Test
    fun `cannot modify or delete the ledger or the audit log`() {
        for (table in listOf("kardex", "audit_log")) {
            assertRejected("permission denied for table $table") { db.jdbc.sql("UPDATE $table SET note = 'x'").update() }
            assertRejected("permission denied for table $table") { db.jdbc.sql("DELETE FROM $table").update() }
        }
    }

    @Test
    fun `reads the migration history but does not write it`() {
        val version = db.jdbc.sql("SELECT max(version::int) FROM flyway_schema_history").query(Int::class.java).single()
        assertThat(version).isGreaterThanOrEqualTo(12)

        assertRejected("permission denied for table flyway_schema_history") { db.jdbc.sql("DELETE FROM flyway_schema_history").update() }
    }

    @Test
    fun `can ensure an audit log partition, which is born protected`() {
        db.jdbc.sql("SELECT audit_log_ensure_partition(DATE '2031-05-10')").query(String::class.java).single()

        assertRejected("permission denied for table audit_log_2031_05") { db.jdbc.sql("UPDATE audit_log_2031_05 SET note = 'x'").update() }
        // No queda ninguna partición de más: se elimina como dueño para no afectar a otras pruebas.
        db.asOwner.sql("DROP TABLE audit_log_2031_05").update()
    }

    @Test
    fun `the hash chain index exists in the table and in its partitions`() {
        val indexes = db.asOwner
            .sql("SELECT count(*) FROM pg_indexes WHERE indexname LIKE 'audit_log%sequence_no_idx'")
            .query(Int::class.java).single()
        // El de la tabla padre, el de la partición por defecto y uno por cada partición mensual.
        assertThat(indexes).isGreaterThan(2)
    }
}
