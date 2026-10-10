package dev.axzy.stock.application.labeling

import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditActor
import dev.axzy.stock.domain.audit.AuditChannel
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asRight
import java.time.Clock
import java.time.Instant

/**
 * Who calls when it is not a person (docs/api/etiquetas.md).
 *
 * The service credential identifies a system —the printer, and tomorrow the ERP—, and
 * its channel **is not chosen with a header**: the credential sets it. That is why the channel
 * is overwritten in the use case and is not read from the request: if it were read, anyone with
 * a person's token could declare themselves a printer.
 */
data class ServiceIdentity(
    val keyId: Long,
    val name: String,
    val scopes: List<String>,
)

/**
 * The service credentials.
 *
 * The **hash is computed in the adapter**: the application layer does no cryptography, and
 * the secret in clear is not stored anywhere (the contract says it is only
 * returned when created).
 */
interface ServiceKeys {
    /** Looks up an active credential by its secret. `null` if it does not exist, is expired or revoked. */
    fun findActive(secret: String, at: Instant): ServiceIdentity?

    /** Records that it was used: a credential that is never used is one that can be revoked. */
    fun touch(keyId: Long, at: Instant): Unit
}

/**
 * The printer's confirmation: **here and only here does a label become printed**.
 *
 * It is the only route without a user: it requires `X-Service-Key` instead of an access token, and
 * **the credential is checked before anything else**, even before looking at whether the job
 * exists: a `404` to whoever has no credential would tell them which identifiers are valid.
 *
 * Marking a label that is already printed answers `LABEL_ALREADY_PRINTED` and **does not
 * touch it**: a label is not printed twice because of a printer retry.
 */
class ConfirmPrintJob(
    private val jobs: PrintJobs,
    private val keys: ServiceKeys,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        secret: String?,
        jobId: Long,
        status: String?,
        error: String?,
        origin: RequestOrigin,
    ): Outcome<PrintJobView> {
        val identity = keys.findActive(secret ?: "", clock.instant())
            ?: return invalidServiceKey()

        val newStatus = status?.trim()?.uppercase()
        if (newStatus != PRINTED && newStatus != FAILED) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The status must be PRINTED or FAILED.",
                mapOf("field" to "status", "allowed" to "$PRINTED,$FAILED"),
            )
        }

        return tx.inTransaction {
            val job = jobs.findJob(jobId) ?: return@inTransaction jobNotFound(jobId)
            val at = clock.instant()
            val affected = if (newStatus == PRINTED) {
                jobs.markPrinted(jobId, at)
            } else {
                jobs.markFailed(jobId, error?.take(500) ?: "The printer reported a failure without details", at)
            }
            if (affected == 0) {
                // It was already printed: the printer's retry does not print twice.
                return@inTransaction failure(
                    FailureKind.CONFLICT, "LABEL_ALREADY_PRINTED",
                    "Job $jobId was already marked as printed.",
                    mapOf("jobId" to jobId.toString(), "status" to job.status),
                )
            }
            keys.touch(identity.keyId, at)

            /*
             * The credential sets the channel, not the header. And the level is RELEVANT and not
             * CRITICAL on purpose: the database requires that a CRITICAL record carry a permission and an
             * access decision trace, and here there is neither a person nor a permission to
             * decide. Inventing a permission to satisfy the constraint would be lying in
             * the audit log.
             */
            audit.record(
                auditEvents.byActor(
                    actor = AuditActor.system("Printer confirmation '${identity.name}'"),
                    origin = origin.copy(channel = AuditChannel.SERVICE_PRINTER),
                    action = if (newStatus == PRINTED) "PRINT_JOB_PRINTED" else "PRINT_JOB_FAILED",
                    level = AuditLevel.RELEVANT,
                    entity = AuditEntity("print_job", jobId.toString(), job.templateCode),
                    serviceKeyId = identity.keyId,
                    previousState = mapOf("status" to job.status, "attempts" to job.attempts),
                    newState = mapOf("status" to newStatus, "error" to error),
                ),
            )
            jobs.findJob(jobId)?.asRight() ?: jobNotFound(jobId)
        }
    }
}

/** The credential is not valid: `401` and not a hint of which identifiers exist. */
private fun invalidServiceKey(): Outcome<Nothing> = failure(
    FailureKind.UNAUTHENTICATED, "INVALID_SERVICE_KEY",
    "The service credential is missing, not valid or revoked.",
    mapOf("header" to "X-Service-Key"),
)

private fun jobNotFound(id: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "PRINT_JOB_NOT_FOUND",
    "Print job $id does not exist.",
    mapOf("jobId" to id.toString()),
)

private const val PRINTED = "PRINTED"
private const val FAILED = "FAILED"
