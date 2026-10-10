package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the API user CANNOT do in the database (migration V11). If a defect in the API
 * tried any of these things, the database rejects it: it does not depend on the code behaving.
 */
class ApiRolePrivilegesTest {

    private val db = TestPersistence()

    /** Spring wraps the controller's error: what PostgreSQL said is in the root cause. */
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
        // No extra partition is left: it is dropped as owner so as not to affect other tests.
        db.asOwner.sql("DROP TABLE audit_log_2031_05").update()
    }

    @Test
    fun `the hash chain index exists in the table and in its partitions`() {
        val indexes = db.asOwner
            .sql("SELECT count(*) FROM pg_indexes WHERE indexname LIKE 'audit_log%sequence_no_idx'")
            .query(Int::class.java).single()
        // The parent table's, the default partition's, and one for each monthly partition.
        assertThat(indexes).isGreaterThan(2)
    }
}
