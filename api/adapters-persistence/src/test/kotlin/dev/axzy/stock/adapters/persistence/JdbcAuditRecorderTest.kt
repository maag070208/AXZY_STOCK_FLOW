package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.audit.IndependentAccessDenialLog
import dev.axzy.stock.adapters.persistence.audit.JdbcAuditRecorder
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AbacTrace
import dev.axzy.stock.domain.audit.AccessDecisionTrace
import dev.axzy.stock.domain.audit.AccessTraceBuilder
import dev.axzy.stock.domain.audit.AuditActor
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditEvent
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.audit.AuditedContext
import dev.axzy.stock.domain.audit.ConfigSnapshot
import dev.axzy.stock.domain.audit.IdentityTrace
import dev.axzy.stock.domain.audit.RbacTrace
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.testing.MutableClock
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JdbcAuditRecorderTest {

    private val db = TestPersistence()
    private val events = AuditEvents(MutableClock())
    private val recorder = JdbcAuditRecorder(db.jdbc, db.json)

    @BeforeEach
    fun clean() = TestDatabase.reset()

    private fun event(
        level: AuditLevel = AuditLevel.RELEVANT,
        permissionKey: String? = null,
        trace: AccessDecisionTrace? = null,
    ): AuditEvent = events.byActor(
        actor = AuditActor.system("prueba de bitácora"),
        origin = RequestOrigin.system().copy(ip = "10.0.0.7", requestId = "req-1"),
        action = "PRODUCT_UPDATED",
        level = level,
        entity = AuditEntity(type = "product", id = "42", label = "ABRAA1B"),
        permissionKey = permissionKey,
        trace = trace,
        previousState = mapOf("name" to "Antes", "active" to true),
        newState = mapOf("name" to "Después", "active" to true),
        reason = "corrección",
    )

    private fun auditRows(): Int = db.jdbc.sql("SELECT count(*) FROM audit_log").query(Int::class.java).single()

    @Test
    fun `stores the record's dimensions and computes which fields changed`() {
        val id = recorder.record(event())

        val row = db.jdbc.sql(
            """
            SELECT action, source_channel::text AS channel, host(source_ip) AS ip, entity_type, entity_id, entity_label,
                   actor_username, reason, request_id, array_to_string(changed_fields, ',') AS changed,
                   new_state ->> 'name' AS new_name, prev_hash, length(record_hash) AS hash_length
            FROM audit_log WHERE id = :id
            """.trimIndent(),
        ).param("id", id).query().singleRow()

        assertThat(row).containsEntry("action", "PRODUCT_UPDATED")
            .containsEntry("channel", "SYSTEM")
            .containsEntry("ip", "10.0.0.7")
            .containsEntry("entity_type", "product")
            .containsEntry("entity_id", "42")
            .containsEntry("entity_label", "ABRAA1B")
            .containsEntry("actor_username", "system")
            .containsEntry("reason", "corrección")
            .containsEntry("request_id", "req-1")
            // Solo `name` cambió: `active` vale lo mismo antes y después.
            .containsEntry("changed", "name")
            .containsEntry("new_name", "Después")
            .containsEntry("prev_hash", "GENESIS")
            .containsEntry("hash_length", 64)
    }

    @Test
    fun `every record is chained to the previous one and the chain verifies intact`() {
        repeat(5) { recorder.record(event()) }

        val linked = db.jdbc.sql(
            """
            SELECT count(*) FROM audit_log a
            JOIN audit_log anterior ON anterior.sequence_no = a.sequence_no - 1
            WHERE a.prev_hash = anterior.record_hash
            """.trimIndent(),
        ).query(Int::class.java).single()
        assertThat(linked).isEqualTo(4)

        val breaks = db.jdbc.sql("SELECT count(*) FROM audit_log_verify_chain()").query(Int::class.java).single()
        assertThat(breaks).isZero()
    }

    @Test
    fun `U1 - if the use case fails, its audit record is not kept either`() {
        val outcome = db.tx.inTransaction {
            recorder.record(event())
            failure(FailureKind.RULE_VIOLATION, "INSUFFICIENT_STOCK", "No hay existencias.")
        }

        assertThat(outcome.isLeft).isTrue()
        assertThat(auditRows()).isZero()
    }

    @Test
    fun `if the use case ends well, the record is kept`() {
        db.tx.inTransaction { recorder.record(event()).asRight() }

        assertThat(auditRows()).isEqualTo(1)
    }

    @Test
    fun `U3 - a critical record without an access trace is rejected by the database`() {
        assertThatThrownBy { recorder.record(event(level = AuditLevel.CRITICAL, permissionKey = "inventory.adjust_theft")) }
            .rootCause().hasMessageContaining("audit_critical_has_trace")
        assertThat(auditRows()).isZero()
    }

    @Test
    fun `a critical record with its trace is saved, and the trace can be read`() {
        val trace = AccessTraceBuilder.build(
            permissionKey = "inventory.adjust_theft",
            decisionCode = "OK",
            stage = "ABAC",
            allowed = true,
            identity = IdentityTrace(userId = 7, active = true),
            rbac = RbacTrace(effectiveScope = "ALL", byRole = mapOf("ADMIN" to "ALL"), rolesAtTime = listOf("ADMIN")),
            abac = AbacTrace(evaluated = true, allowed = true, decidedByPolicyId = 4),
            context = AuditedContext(hour = 10, weekday = 1, deviceType = "WEB", offlineSync = false),
            snapshot = ConfigSnapshot(matrixVersion = 3, policiesVersion = 2),
        )

        val id = recorder.record(event(level = AuditLevel.CRITICAL, permissionKey = "inventory.adjust_theft", trace = trace))

        val row = db.jdbc.sql(
            """
            SELECT decision::text AS decision, decision_stage, matrix_version, policies_version,
                   access_trace -> 'rbac' ->> 'effectiveScope' AS scope,
                   (access_trace -> 'abac' ->> 'decidedByPolicyId')::int AS policy
            FROM audit_log WHERE id = :id
            """.trimIndent(),
        ).param("id", id).query().singleRow()

        assertThat(row).containsEntry("decision", "ALLOWED")
            .containsEntry("decision_stage", "ABAC")
            .containsEntry("matrix_version", 3)
            .containsEntry("policies_version", 2)
            .containsEntry("scope", "ALL")
            .containsEntry("policy", 4)
    }

    @Test
    fun `a denial is written even if the use case that caused it is rolled back`() {
        val denials = IndependentAccessDenialLog(recorder, db.independentTx)

        db.tx.inTransaction {
            denials.record(event())
            failure(FailureKind.FORBIDDEN, "PERMISSION_DENIED", "No tienes permiso.")
        }

        assertThat(auditRows()).isEqualTo(1)
    }

    @Test
    fun `an address that is not an IP does not break the record`() {
        val conOrigenRaro = event().let { it.copy(source = it.source.copy(ip = "unknown")) }

        val id = recorder.record(conOrigenRaro)

        val ip = db.jdbc.sql("SELECT source_ip::text FROM audit_log WHERE id = :id").param("id", id)
            .query(String::class.java).optional().orElse(null)
        assertThat(ip).isNull()
    }
}
