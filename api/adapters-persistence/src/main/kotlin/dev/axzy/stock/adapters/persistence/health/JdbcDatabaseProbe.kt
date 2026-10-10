package dev.axzy.stock.adapters.persistence.health

import dev.axzy.stock.application.health.DatabaseHealth
import dev.axzy.stock.application.health.DatabaseProbe
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Comprueba la base con una consulta real. Distingue dos preguntas: «¿responde?», que decide el
 * estado, y «¿qué versión de esquema tiene?», que es solo diagnóstico.
 */
class JdbcDatabaseProbe(private val jdbc: JdbcClient) : DatabaseProbe {

    override fun probe(): DatabaseHealth {
        val startedAt = System.nanoTime()
        try {
            jdbc.sql("SELECT 1").query(Int::class.java).single()
        } catch (e: Exception) {
            return DatabaseHealth(up = false, latencyMs = elapsedMs(startedAt), schemaVersion = null, error = e.rootMessage())
        }
        val latencyMs = elapsedMs(startedAt)

        return try {
            val version = jdbc
                .sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
                .query(String::class.java)
                .optional()
                .orElse(null)
            DatabaseHealth(
                up = true,
                latencyMs = latencyMs,
                schemaVersion = version,
                error = if (version == null) "The migration history is empty." else null,
            )
        } catch (e: Exception) {
            DatabaseHealth(up = true, latencyMs = latencyMs, schemaVersion = null, error = e.rootMessage())
        }
    }

    private fun elapsedMs(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

    /** Solo el tipo del fallo: el mensaje del controlador puede llevar el host o el usuario de la base. */
    private fun Exception.rootMessage(): String {
        var cause: Throwable = this
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.javaClass.simpleName
    }
}
