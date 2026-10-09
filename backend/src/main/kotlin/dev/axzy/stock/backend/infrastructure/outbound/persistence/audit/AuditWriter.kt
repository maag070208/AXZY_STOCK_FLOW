package dev.axzy.stock.backend.infrastructure.outbound.persistence.audit

import dev.axzy.stock.backend.application.audit.AccessDenial
import dev.axzy.stock.backend.application.audit.AccessDenialLog
import dev.axzy.stock.backend.application.audit.AuditDecision
import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.backend.application.audit.AuditLevel
import dev.axzy.stock.backend.application.audit.AuditResult
import java.sql.Connection
import java.sql.Types
import java.util.UUID
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ESCRITOR DE AUDITORÍA — el único sitio que escribe en `audit_log`
 *
 * **No hay SQL de auditoría repartido por los módulos.** Todo pasa por [record], y esa
 * centralización es lo que permite cambiar la cadena de hash, añadir una columna o corregir
 * un mapeo en un solo sitio.
 *
 * ## La auditoría NO es «best effort»
 *
 * [record] recibe la **conexión del llamador** y participa de su transacción. Si la auditoría
 * falla, la operación se revierte con ella. Es deliberado: si un movimiento de inventario se
 * aplica y su registro no, el inventario cambió sin dejar rastro de quién lo cambió, y eso se
 * descubre un año después. Un sistema que a veces audita es más peligroso que uno que no
 * audita, porque genera confianza sin merecerla.
 *
 * El precio: si la tabla de auditoría no está disponible, el almacén deja de poder operar. Es
 * correcto para una operación que mueve patrimonio. La única excepción es [recordDenied].
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AuditWriter(private val dataSource: DataSource) : AccessDenialLog {

    /**
     * Escribe un registro **dentro de la transacción de quien llama**.
     *
     * @param connection la conexión del llamador. No se abre una nueva: el registro tiene que
     *   confirmarse o revertirse con la operación que audita.
     * @return el id del registro.
     */
    fun record(connection: Connection, entry: AuditEntry): Long {
        val sql = """
            SELECT audit_log_record(
                ?::bigint, ?::text, ?::text, ?::text[],
                ?::audit_channel, ?::inet, ?::text, ?::text,
                ?::text, ?::text, ?::text,
                ?::text, ?::audit_level, ?::jsonb, ?::jsonb, ?::text, ?::text,
                ?::text, ?::audit_decision, ?::text,
                ?::audit_result, ?::text, ?::text, ?::integer,
                ?::uuid, ?::uuid, ?::text, ?::text,
                ?::date
            )
        """.trimIndent()

        // `CRITICAL` exige trazabilidad. Si quien llama no dio una correlación, se genera aquí en
        // lugar de dejar que el INSERT falle: perder el registro de una operación crítica por un
        // detalle de fontanería sería peor que el detalle.
        val correlation = entry.correlationId
            ?: if (entry.level == AuditLevel.CRITICAL) newCorrelationId() else null

        // Una IP que no es IP (un `localhost` sin resolver, una cabecera manipulada) haría fallar
        // el CAST a `inet` y tumbaría la operación por un dato accesorio. Se descarta.
        val validIp = entry.sourceIp?.takeIf { looksLikeIp(it) }

        // El nombre del actor se resuelve AQUÍ y no en cada llamador: los casos de uso solo tienen
        // el id, y una bitácora que no dice el nombre de la persona obliga a cruzar con `users`
        // para leerla. Sin consulta adicional cuando el llamador ya lo manda.
        val actorName = entry.actorUsername ?: resolveActorName(connection, entry.actorUserId)

        connection.prepareStatement(sql).use { st ->
            var i = 1
            st.setObject(i++, entry.actorUserId, Types.BIGINT)
            st.setString(i++, actorName)
            st.setString(i++, actorName)
            st.setArray(i++, entry.actorRoles?.let { connection.createArrayOf("text", it.toTypedArray()) })
            st.setString(i++, entry.channel.name)
            st.setString(i++, validIp)
            st.setString(i++, entry.sourceUserAgent)
            st.setString(i++, entry.sourceDevice)
            st.setString(i++, entry.entityType)
            st.setString(i++, entry.entityId)
            st.setString(i++, entry.entityLabel)
            st.setString(i++, entry.action)
            st.setString(i++, entry.level.name)
            st.setString(i++, entry.previousState)
            st.setString(i++, entry.newState)
            st.setString(i++, entry.reason)
            st.setString(i++, entry.note)
            st.setString(i++, entry.permissionKey)
            st.setString(i++, entry.decision.name)
            st.setString(i++, entry.decisionCode)
            st.setString(i++, entry.result.name)
            st.setString(i++, entry.errorCode)
            st.setString(i++, entry.errorMessage)
            st.setObject(i++, entry.durationMs, Types.INTEGER)
            st.setString(i++, correlation)
            st.setString(i++, entry.causationId)
            st.setString(i++, entry.idempotencyKey)
            st.setString(i++, entry.requestId)
            st.setObject(i, entry.businessDate, Types.DATE)

            st.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /**
     * Forma anterior de [record], con parámetros sueltos. **Temporal**: existe solo para que los
     * módulos que aún no se han pasado a capas sigan compilando; se retira al terminar la migración.
     */
    @Suppress("LongParameterList")
    fun record(
        connection: Connection,
        actorUserId: Long?,
        actorUsername: String?,
        channel: dev.axzy.stock.backend.application.audit.AuditChannel,
        sourceIp: String? = null,
        entityType: String,
        entityId: String? = null,
        entityLabel: String? = null,
        action: String,
        level: AuditLevel = AuditLevel.RELEVANT,
        previousState: String? = null,
        newState: String? = null,
        reason: String? = null,
        note: String? = null,
        permissionKey: String? = null,
        decision: AuditDecision = AuditDecision.ALLOWED,
        result: AuditResult = AuditResult.SUCCESS,
        idempotencyKey: String? = null,
        businessDate: java.time.LocalDate? = null,
    ): Long = record(
        connection,
        AuditEntry(
            actorUserId = actorUserId,
            actorUsername = actorUsername,
            channel = channel,
            sourceIp = sourceIp,
            entityType = entityType,
            entityId = entityId,
            entityLabel = entityLabel,
            action = action,
            level = level,
            previousState = previousState,
            newState = newState,
            reason = reason,
            note = note,
            permissionKey = permissionKey,
            decision = decision,
            result = result,
            idempotencyKey = idempotencyKey,
            businessDate = businessDate,
        ),
    )

    /**
     * Registra un **acceso denegado**, y se traga el fallo.
     *
     * Es la única excepción deliberada a «si la auditoría falla, la operación falla»: cuando
     * alguien intenta algo sin permiso, la respuesta correcta es **403**, no un 500 porque la
     * tabla de auditoría está caída. Abre su propia conexión porque el acceso denegado no forma
     * parte de ninguna transacción de negocio.
     *
     * Devuelve el id, o `null` si no se pudo registrar. El `null` es la admisión de que el
     * registro se perdió, no un error.
     */
    override fun recordDenied(denial: AccessDenial): Long? = runCatching {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            record(
                connection,
                AuditEntry(
                    actorUserId = denial.actorUserId,
                    actorUsername = denial.actorUsername,
                    channel = denial.channel,
                    sourceIp = denial.sourceIp,
                    entityType = denial.entityType,
                    entityId = denial.entityId,
                    action = "access.denied",
                    level = AuditLevel.ACCESS,
                    permissionKey = denial.permissionKey,
                    decision = AuditDecision.DENIED,
                    decisionCode = "FORBIDDEN",
                    result = AuditResult.DENIED,
                    reason = denial.reason,
                ),
            )
        }
    }.getOrNull()

    /**
     * El nombre de usuario a partir de su id. `null` si el id es nulo o el usuario no existe, y
     * no es un error: se audita a un actor anónimo en un intento fallido.
     */
    private fun resolveActorName(connection: Connection, userId: Long?): String? {
        if (userId == null) return null
        return connection.prepareStatement("SELECT username FROM users WHERE id = ?").use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    companion object {
        /**
         * Una correlación nueva, para seguir una operación completa. No se usa el `requestId`:
         * ese identifica UNA petición HTTP, y una operación de negocio puede abarcar varias.
         */
        fun newCorrelationId(): String = UUID.randomUUID().toString()

        /**
         * ¿Parece una dirección IP? Comprobación laxa a propósito: solo descarta lo que PostgreSQL
         * rechazaría al castear a `inet`. `X-Forwarded-For` es falsificable, así que la IP es un
         * dato indicativo, no una prueba.
         */
        private fun looksLikeIp(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return false
            return trimmed.matches(Regex("^[0-9a-fA-F.:]+$"))
        }
    }
}
