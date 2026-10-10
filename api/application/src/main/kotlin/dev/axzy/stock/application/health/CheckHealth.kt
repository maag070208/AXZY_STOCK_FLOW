package dev.axzy.stock.application.health

/** What the service knows about its database at this moment. */
data class DatabaseHealth(
    val up: Boolean,
    val latencyMs: Long,
    /** The last migration applied, or `null` if the history could not be read. */
    val schemaVersion: String?,
    val error: String? = null,
)

data class HealthReport(
    val up: Boolean,
    val version: String,
    val database: DatabaseHealth,
)

/** Makes a real query to the database. A health check that checks nothing says "healthy" with the database down. */
fun interface DatabaseProbe {
    fun probe(): DatabaseHealth
}

/**
 * The service status (docs/api/salud.md). The database decides it: `schemaVersion` is
 * diagnostic and does not change the status.
 */
class CheckHealth(
    private val database: DatabaseProbe,
    private val version: String,
) {
    fun execute(): HealthReport {
        val health = database.probe()
        return HealthReport(up = health.up, version = version, database = health)
    }
}
