package dev.axzy.stock.backend.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * HEALTH
 *
 * Un health que solo responde "ok" sin comprobar nada es peor que no tenerlo:
 * dice que el sistema está sano mientras la base de datos está caída.
 *
 * Pero un health que da **falsos negativos** es igual de malo, y en la otra
 * dirección: hace que un orquestador reinicie instancias que están sanas. Ese
 * defecto lo tuvo la primera versión de este archivo —informaba `DOWN` cuando la
 * base respondía pero faltaba la tabla de historial de Flyway— y está corregido
 * separando dos preguntas que no son la misma:
 *
 *   1. **¿La base responde?** → decide el estado. Solo un fallo de conexión o de
 *      consulta la marca caída.
 *   2. **¿Qué versión de esquema tiene?** → es información de diagnóstico, no de
 *      salud. Si no se puede leer, el estado sigue siendo `UP` y se informa del
 *      motivo.
 * ═══════════════════════════════════════════════════════════════════════════
 */

@Serializable
data class HealthResponse(
    val status: String,
    /** Versión del esquema aplicado. `null` si no se pudo determinar. */
    val schemaVersion: String?,
    val database: ComponentHealth,
    val version: String,
)

@Serializable
data class ComponentHealth(
    val status: String,
    /** Latencia de la comprobación, en milisegundos. */
    val latencyMs: Long,
    /** Motivo del fallo, o de la imposibilidad de leer la versión. */
    val error: String? = null,
)

private const val STATE_UP = "UP"
private const val STATE_DOWN = "DOWN"

/**
 * Resultado de la comprobación.
 *
 * Se distingue «la base no responde» de «la base responde pero no tiene
 * historial de migraciones». La primera es una caída; la segunda es un
 * despliegue que hay que revisar, y confundirlas hace que el operador busque el
 * problema en el sitio equivocado.
 */
private data class DatabaseCheck(
    val reachable: Boolean,
    val schemaVersion: String?,
    val note: String?,
)

private fun checkDatabase(dataSource: DataSource): DatabaseCheck {
    // ── Paso 1: ¿responde? ──────────────────────────────────────────────────
    // Una consulta real y trivial. Si esto falla, la base está caída o el pool
    // no tiene conexiones: es un `DOWN` de verdad.
    try {
        dataSource.connection.use { conexion ->
            conexion.createStatement().use { sentencia ->
                sentencia.executeQuery("SELECT 1").use { filas ->
                    if (!filas.next()) return DatabaseCheck(false, null, "La base no respondió a la consulta")
                }
            }
        }
    } catch (e: Exception) {
        return DatabaseCheck(false, null, e.message ?: "No se pudo conectar a la base de datos")
    }

    // ── Paso 2: ¿qué versión de esquema? ────────────────────────────────────
    // La base YA respondió, así que un fallo aquí NO es una caída. Se informa
    // del motivo para que un despliegue a medias se diagnostique rápido.
    val version = try {
        dataSource.connection.use { conexion ->
            conexion.createStatement().use { sentencia ->
                sentencia.executeQuery(
                    "SELECT version FROM flyway_schema_history " +
                        "WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"
                ).use { filas ->
                    if (filas.next()) filas.getString("version") else null
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    val nota = if (version == null) {
        "La base responde pero no se pudo leer la versión del esquema " +
            "(¿faltan las migraciones de Flyway?)"
    } else {
        null
    }

    return DatabaseCheck(reachable = true, schemaVersion = version, note = nota)
}

fun Route.healthRoutes(dataSource: DataSource) {

    get("/v1/health") {
        val inicio = System.nanoTime()
        val comprobacion = checkDatabase(dataSource)
        val latencia = (System.nanoTime() - inicio) / 1_000_000

        val estado = if (comprobacion.reachable) STATE_UP else STATE_DOWN

        val respuesta = HealthResponse(
            status = estado,
            schemaVersion = comprobacion.schemaVersion,
            database = ComponentHealth(
                status = estado,
                latencyMs = latencia,
                // El motivo se informa siempre que haya algo que decir, sea una
                // caída o una versión ilegible.
                error = comprobacion.note,
            ),
            version = System.getenv("STOCK_VERSION") ?: "dev",
        )

        // 503 SOLO si la base no responde: así el orquestador saca de rotación
        // únicamente las instancias que de verdad no pueden servir.
        val codigo = if (comprobacion.reachable) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(codigo, respuesta)
    }
}
