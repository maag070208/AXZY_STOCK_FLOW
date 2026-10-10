package dev.axzy.stock.domain.audit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AUDIT MODEL — "EVERYTHING must be auditable"
 *
 * The requirement is of first order: **who, when, where, from where, at what time
 * and with which permission**. The six dimensions are represented here as
 * campos obligatorios, no como buenas intenciones.
 *
 * **The point that is usually overlooked:** auditing the change is not enough: one has to
 * audit **the authorization**. Permissions change over time. If within
 * six months someone asks whether an approval of 200 pieces was legitimate,
 * looking at the user's *current* permissions answers nothing: one has to know which
 * roles, what scope and **which policies were in force at the moment of the**
 * **action**. That is why each event carries the *trace* of the full decision and the
 * **version** of the matrix and of the policies.
 *
 * **The legacy has none of this.** Verified over the 13 models of its
 * schema: there is no audit table at all. Its only trail is the `Kardex`,
 * and it is **mutable** (`removeUnitsFromKardex` does `kardex.update`), so
 * it does not serve as a historical record either. The consequence is that the legacy
 * system **cannot answer "who did this"** for almost anything.
 *
 * Referencia: docs/historico/PLAN_AUDITORIA.md.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Source channel of an event.
 *
 * It is the dimension the legacy **did not have at all**, and without it a movement cannot be
 * interpreted without a person behind it: if an inventory adjustment appears
 * with no user, one has to be able to tell "the ERP did it" from "someone
 * with a leaked API key" from "it is a bug".
 */
enum class AuditChannel {
    /** A person from the browser. */
    WEB,

    /** A person from the mobile app, online. */
    MOBILE,

    /** A person on the mobile app **without signal**, synchronized later. */
    MOBILE_OFFLINE,

    /** External system: the ERP (formerly `kardo`). */
    SERVICE_ERP,

    /** The Zebra printer confirming printing. */
    SERVICE_PRINTER,

    /** Internal process: reconciliation, expiry of exceptions. */
    SERVICE_JOB,

    /** Initial data load, in the migration. */
    MIGRATION,

    /** The system itself: startup, schema change. */
    SYSTEM,
    ;

    /** Indicates whether the event was originated by a person. */
    val isHuman: Boolean get() = this == WEB || this == MOBILE || this == MOBILE_OFFLINE

    /** Indicates whether the event came from an integration with a service credential. */
    val isService: Boolean
        get() = this == SERVICE_ERP || this == SERVICE_PRINTER || this == SERVICE_JOB
}

/**
 * Criticality level, which determines the handling and the retention.
 *
 * **Recording everything with the same detail is not auditing: it is accumulating noise that**
 * nobody will read.** A paginated inventory listing generates dozens of records,
 * and the write volume exceeds the real read volume. The solution is not to
 * audit less, but to separate by levels.
 */
enum class AuditLevel(val numeric: Int) {
    /**
     * Moves inventory, money or access. Complete record **in the same**
     * **transaction** as the operation, and **permanent** retention.
     */
    CRITICAL(1),

    /** Changes people's work. Complete record, 3-year retention. */
    RELEVANT(2),

    /**
     * Data access. **Aggregate counter** for normal reads, and
     * a complete record only for sensitive data and for denials.
     * Retention 1 year.
     */
    ACCESS(3),

    /** Observability, not auditing. Retention 30 days. */
    TECHNICAL(4),
}

/** Result of the audited operation. */
enum class AuditResult {
    SUCCESS,
    FAILURE,

    /**
     * Denied by the access engine.
     *
     * **Denied attempts are the most valuable signal of the audit**: they are
     * the ones that detect abuse, and also the ones that detect a misconfigured permission that
     * is blocking legitimate work.
     */
    DENIED,
    PARTIAL,
}

/**
 * Who executed the action.
 *
 * **The fields include a snapshot of the name and the roles**, not just the id.
 * The reason: if the user is renamed or deleted, a FK alone leaves the history
 * unreadable. An audit that cannot be read two years from now is no use.
 */
data class AuditActor(
    val userId: Long?,
    val username: String?,
    val displayName: String?,
    /** Snapshot of the roles **in force at the time** of the action. */
    val roles: List<String> = emptyList(),
) {
    companion object {
        fun system(reason: String) = AuditActor(
            userId = null,
            username = "system",
            displayName = reason,
            roles = emptyList(),
        )

        /** Actor of an integration with a service credential. */
        fun service(name: String, keyId: Long) = AuditActor(
            userId = null,
            username = "service:$name",
            displayName = "Integration $name (key #$keyId)",
            roles = emptyList(),
        )
    }
}

/** Where the request came from. */
data class AuditSource(
    val channel: AuditChannel,
    val deviceId: String? = null,
    val ip: String? = null,
    val userAgent: String? = null,
    /** If it came from an integration, the service key used. */
    val serviceKeyId: Long? = null,
)

/** What was acted upon. */
data class AuditEntity(
    val type: String,
    val id: String?,
    /**
     * Etiqueta legible: `"Factura F-2024-0158"`.
     *
     * Without this, an audit report is a list of ids that nobody can interpret
     * without querying five tables.
     */
    val label: String? = null,
)

/**
 * Times of the event.
 *
 * **Two different times are stored on purpose.** The mobile app can capture at
 * 22:00 without signal and synchronize at 09:00 the next day. These are two different facts
 * and both matter: the first says when it happened, the second when
 * it was known. The legacy mixed `Z` with local time and stored neither of the
 * two reliably (finding R-15).
 */
data class AuditTiming(
    /** Instant in UTC when the action occurred. */
    val occurredAt: Long,
    /** Operating date: for night shifts, the "business date". */
    val businessDate: String,
    /** When the mobile app captured it without signal. `null` if there was a connection. */
    val offlineCreatedAt: Long? = null,
    /** When it reached the server. `null` if it was online. */
    val syncedAt: Long? = null,
    /** Difference detected against the server clock, in milliseconds. */
    val clockSkewMs: Long? = null,
) {
    /** Indicates whether the event arrived through deferred synchronization. */
    val isDeferred: Boolean get() = offlineCreatedAt != null && offlineCreatedAt != occurredAt
}

/**
 * A complete audit event: **the six dimensions**.
 *
 * It is immutable. Once built, it is not modified: an audit log that can be
 * edited is not an audit log.
 */
data class AuditEvent(
    // ── WHEN ────────────────────────────────────────────────────────────────
    val timing: AuditTiming,

    // ── WHO ─────────────────────────────────────────────────────────────────
    val actor: AuditActor,

    // ── FROM WHERE ────────────────────────────────────────────────────────────
    val source: AuditSource,

    // ── ON WHAT ───────────────────────────────────────────────────────────
    val entity: AuditEntity,

    // ── WHAT ─────────────────────────────────────────────────────────────────
    val action: String,
    val level: AuditLevel,

    // ── WITH WHAT PERMISSION ─────────────────────────────────────────────────
    val permissionKey: String? = null,
    val trace: AccessDecisionTrace? = null,

    // ── WHAT CHANGED ──────────────────────────────────────────────────────────
    val previousState: Map<String, Any?>? = null,
    val newState: Map<String, Any?>? = null,
    val changedFields: List<String> = emptyList(),

    // ── WHY ─────────────────────────────────────────────────────────────────
    val reason: String? = null,
    val note: String? = null,

    // ── RESULTADO ───────────────────────────────────────────────────────────
    val result: AuditResult = AuditResult.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,

    // ── CORRELATION ─────────────────────────────────────────────────────────
    /** Groups all the actions of the same flow (a receiving, a closing). */
    val correlationId: String? = null,
    val causationId: String? = null,
    /** The mobile app's idempotency key: it allows deduplicating retries. */
    val idempotencyKey: String? = null,
    val requestId: String? = null,

    // ── INTEGRIDAD ──────────────────────────────────────────────────────────
    val sequence: Long = 0,
    val previousHash: String? = null,
    val recordHash: String = "",
) {
    /**
     * Indicates whether the event has the **six dimensions** complete.
     *
     * It is the check that makes "everything auditable" verifiable instead of
     * aspirational: an event without a source channel or without an occurrence time
     * is incomplete, and a test detects it.
     */
    val hasAllDimensions: Boolean
        get() {
            // WHO: if the channel is human, a person must be identified.
            // If it is a service or system, the origin must be identified.
            val hasActor = when {
                source.channel.isHuman -> actor.userId != null
                else -> actor.username != null
            }
            // WHEN: instant and operating date.
            val hasWhen = timing.occurredAt > 0 && timing.businessDate.isNotBlank()
            // WHERE: on which entity.
            val hasWhere = entity.type.isNotBlank()
            // AT WHAT TIME, in the sense of whether it was deferred: if it arrived by
            // synchronization, the time it was captured must be recorded.
            val hasTimesCoherent = !timing.isDeferred || timing.offlineCreatedAt != null
            // WHAT and WITH WHAT PERMISSION: the action always; the permission if it is critical.
            val hasThat = action.isNotBlank()
            val hasPermission = level != AuditLevel.CRITICAL || permissionKey != null

            return hasActor && hasWhen && hasWhere &&
                hasTimesCoherent && hasThat && hasPermission
        }

    /** The fields that differ between the previous and the new state. */
    fun computeChangedFields(): List<String> {
        val before = previousState ?: return emptyList()
        val after = newState ?: return emptyList()
        return (before.keys + after.keys).distinct()
            .filter { before[it] != after[it] }
            .sorted()
    }
}

/**
 * The hash chain.
 *
 * It does not prevent alteration: it makes it **detectable**. It is the most that can be
 * achieved without specialized hardware, and it is the same logic as an accounting ledger
 * with numbered pages. If someone modifies a record in the middle, the chain breaks at that
 * exact point and the verification reports it.
 *
 * **Accepted and documented residual risk** (decision R-79): a database administrator
 * with full access can recompute the entire chain. What is detected is
 * any accidental alteration, any change made through the application, and
 * any `UPDATE`/`DELETE` that does not recompute the whole
 * chain. If the requirement changes to "not even the DBA can alter without it being noticed", the
 * mitigation is to export the hashes to immutable external storage; the
 * export port is already prepared for that case.
 */
interface AuditHashChain {

    /** Computes the hash of an event by chaining it with the previous one. */
    fun hash(previousHash: String?, event: AuditEvent): String

    /**
     * Verifies a complete sequence.
     * @return the index of the first invalid event, or `null` if the chain is intact.
     */
    fun verify(events: List<AuditEvent>): Int?
}

/**
 * Deterministic implementation of the chain, with no external dependencies.
 *
 * It uses a simple and stable hash: what matters is not cryptographic strength
 * against an attacker with resources, but that **any change produces a
 * different hash**. The periodic verification detects the alteration.
 */
object DefaultAuditHashChain : AuditHashChain {

    override fun hash(previousHash: String?, event: AuditEvent): String {
        val material = buildString {
            append(previousHash ?: "GENESIS")
            append('|')
            append(event.sequence)
            append('|')
            append(event.timing.occurredAt)
            append('|')
            append(event.actor.userId ?: event.actor.username ?: "?")
            append('|')
            append(event.action)
            append('|')
            append(event.entity.type)
            append('|')
            append(event.entity.id ?: "")
            append('|')
            // The new state is part of the hash: altering a value without recomputing
            // the hash breaks the chain.
            append(event.newState?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value}" } ?: "")
            append('|')
            append(event.reason ?: "")
        }
        return fnv1a64(material).toString(radix = 16).padStart(16, '0')
    }

    override fun verify(events: List<AuditEvent>): Int? {
        var previous: String? = null
        events.forEachIndexed { index, event ->
            val expected = hash(previous, event)
            if (event.recordHash != expected) return index
            previous = expected
        }
        return null
    }

    /**
     * FNV-1a 64-bit. It is the same algorithm that the
     * project's reference implementation uses for the idempotency fingerprint, so there is
     * only one non-cryptographic hashing algorithm in the whole system.
     */
    private fun fnv1a64(input: String): ULong {
        var hash = 0xcbf29ce484222325UL
        input.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3UL
        }
        return hash
    }
}
