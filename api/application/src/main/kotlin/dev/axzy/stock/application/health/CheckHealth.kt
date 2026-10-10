package dev.axzy.stock.application.health

/** Lo que el servicio sabe de su base de datos en este momento. */
data class DatabaseHealth(
    val up: Boolean,
    val latencyMs: Long,
    /** La última migración aplicada, o `null` si no se pudo leer el historial. */
    val schemaVersion: String?,
    val error: String? = null,
)

data class HealthReport(
    val up: Boolean,
    val version: String,
    val database: DatabaseHealth,
)

/** Hace una consulta real a la base. Un health que no comprueba nada dice «sano» con la base caída. */
fun interface DatabaseProbe {
    fun probe(): DatabaseHealth
}

/**
 * El estado del servicio (docs/api/salud.md). Lo decide la base de datos: `schemaVersion` es
 * diagnóstico y no cambia el estado.
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
