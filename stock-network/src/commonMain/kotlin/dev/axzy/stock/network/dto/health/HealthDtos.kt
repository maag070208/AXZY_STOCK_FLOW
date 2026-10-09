package dev.axzy.stock.network.dto.health

import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SALUD — DTOs de `/v1/health`
 *
 * Contrato: docs/historico/CONTRATO_API.md §10. Es el único endpoint **público, sin
 * autenticación**, y el que la pantalla de configuración del móvil usa para
 * probar la URL del servidor antes de pedir credenciales.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * El estado del sistema.
 *
 * `schemaVersion` es **información de diagnóstico**: si no se puede leer, el
 * estado sigue siendo `UP` y el motivo se informa en `database.error`. Por eso
 * aquí es un texto y no un número: una versión de esquema ilegible es un dato
 * ausente, no un fallo del servicio.
 */
@Serializable
data class HealthDto(
    val status: HealthStatus = HealthStatus.DOWN,
    val schemaVersion: String? = null,
    val version: String? = null,
    val database: DatabaseHealthDto? = null,
)

/** `UP` o `DOWN`. Con `DOWN`, el contrato responde `503`. */
@Serializable
enum class HealthStatus {
    @kotlinx.serialization.SerialName("UP")
    UP,

    @kotlinx.serialization.SerialName("DOWN")
    DOWN,
}

/**
 * El estado de la base.
 *
 * `latencyMs` sirve para distinguir «la base está lenta» de «la base no está»,
 * que son dos incidencias distintas con dos respuestas distintas.
 */
@Serializable
data class DatabaseHealthDto(
    val status: HealthStatus = HealthStatus.DOWN,
    val latencyMs: Long? = null,
    val error: String? = null,
)
