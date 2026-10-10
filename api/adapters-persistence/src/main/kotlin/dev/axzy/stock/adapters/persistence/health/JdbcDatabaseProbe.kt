package dev.axzy.stock.adapters.persistence.health

import dev.axzy.stock.application.health.DatabaseHealth
import dev.axzy.stock.application.health.DatabaseProbe
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Checks the database with a real query. It distinguishes two questions: "does it respond?", which decides the
 * status, and "what schema version does it have?", which is only diagnostic.
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

    /** Only the failure type: the controller message may carry the database host or user. */
    private fun Exception.rootMessage(): String {
        var cause: Throwable = this
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.javaClass.simpleName
    }
}
