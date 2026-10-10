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
 * Quién llama cuando no es una persona (docs/api/etiquetas.md).
 *
 * La credencial de servicio identifica a un sistema —la impresora, y mañana el ERP—, y
 * su canal **no se elige con una cabecera**: lo fija la credencial. Por eso el canal se
 * sobrescribe en el caso de uso y no se lee de la petición: si se leyera, cualquiera con
 * un token de persona podría declararse impresora.
 */
data class ServiceIdentity(
    val keyId: Long,
    val name: String,
    val scopes: List<String>,
)

/**
 * Las credenciales de servicio.
 *
 * El **hash se calcula en el adaptador**: la capa de aplicación no hace criptografía, y
 * el secreto en claro no se guarda en ninguna parte (el contrato dice que solo se
 * devuelve al crearlo).
 */
interface ServiceKeys {
    /** Busca una credencial activa por su secreto. `null` si no existe, está vencida o revocada. */
    fun findActive(secret: String, at: Instant): ServiceIdentity?

    /** Deja constancia de que se usó: una credencial que no se usa nunca es una que se puede revocar. */
    fun touch(keyId: Long, at: Instant): Unit
}

/**
 * La confirmación de la impresora: **aquí y solo aquí una etiqueta pasa a impresa**.
 *
 * Es la única ruta sin usuario: exige `X-Service-Key` en lugar de un token de acceso, y
 * **la credencial se comprueba antes que nada**, incluso antes de mirar si el trabajo
 * existe: un `404` a quien no tiene credencial le diría qué identificadores son válidos.
 *
 * Marcar una etiqueta que ya está impresa responde `LABEL_ALREADY_PRINTED` y **no la
 * toca**: una etiqueta no se imprime dos veces por un reintento de la impresora.
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
                "El estado tiene que ser PRINTED o FAILED.",
                mapOf("field" to "status", "allowed" to "$PRINTED,$FAILED"),
            )
        }

        return tx.inTransaction {
            val job = jobs.findJob(jobId) ?: return@inTransaction jobNotFound(jobId)
            val at = clock.instant()
            val affected = if (newStatus == PRINTED) {
                jobs.markPrinted(jobId, at)
            } else {
                jobs.markFailed(jobId, error?.take(500) ?: "La impresora reportó un fallo sin detalle", at)
            }
            if (affected == 0) {
                // Ya estaba impresa: el reintento de la impresora no imprime dos veces.
                return@inTransaction failure(
                    FailureKind.CONFLICT, "LABEL_ALREADY_PRINTED",
                    "El trabajo $jobId ya estaba marcado como impreso.",
                    mapOf("jobId" to jobId.toString(), "status" to job.status),
                )
            }
            keys.touch(identity.keyId, at)

            /*
             * El canal lo fija la credencial, no la cabecera. Y el nivel es RELEVANT y no
             * CRITICAL a propósito: la base exige que un registro CRITICAL lleve permiso y
             * traza de decisión de acceso, y aquí no hay ni persona ni permiso que
             * decidir. Inventar un permiso para satisfacer la restricción sería mentir en
             * la bitácora.
             */
            audit.record(
                auditEvents.byActor(
                    actor = AuditActor.system("Confirmación de la impresora '${identity.name}'"),
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

/** La credencial no vale: `401` y ni una pista de qué identificadores existen. */
private fun invalidServiceKey(): Outcome<Nothing> = failure(
    FailureKind.UNAUTHENTICATED, "INVALID_SERVICE_KEY",
    "La credencial de servicio falta, no es válida o está revocada.",
    mapOf("header" to "X-Service-Key"),
)

private fun jobNotFound(id: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "PRINT_JOB_NOT_FOUND",
    "No existe el trabajo de impresión $id.",
    mapOf("jobId" to id.toString()),
)

private const val PRINTED = "PRINTED"
private const val FAILED = "FAILED"
