package dev.axzy.stock.app.config

import dev.axzy.stock.application.health.DatabaseProbe
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** La versión de esquema con la que arrancó la API. Pedirla como dependencia garantiza que ya se comprobó. */
data class SchemaVersion(val applied: Int)

/**
 * **La API no migra: comprueba.** Las migraciones las aplica el servicio `migrate` (ADR-0016). Si la
 * base está por detrás de lo que este código necesita, la API se niega a arrancar y dice qué falta.
 *
 * El caso que esto evita ya ocurrió: una base de desarrollo se quedó sin una migración durante
 * semanas, y la API arrancaba igual y respondía 500 en las rutas que dependían de ella.
 */
@Configuration(proxyBeanMethods = false)
class SchemaGuard {

    @Bean
    fun schemaVersion(probe: DatabaseProbe): SchemaVersion {
        val health = probe.probe()
        check(health.up) { "Cannot connect to the database (${health.error}). Check STOCK_DB_URL and its credentials." }

        val applied = health.schemaVersion?.toIntOrNull()
            ?: error("The database has no migration history. Run `docker compose up migrate` before starting the API.")
        check(applied >= REQUIRED_SCHEMA_VERSION) {
            "The database is at migration V$applied and this version of the API needs V$REQUIRED_SCHEMA_VERSION. " +
                "Ejecuta `docker compose up migrate`."
        }
        return SchemaVersion(applied)
    }

    companion object {
        /**
         * La última migración de `db/migrations` que este código da por aplicada. Al añadir una
         * migración se sube aquí; una prueba comprueba que coincide con los archivos.
         */
        const val REQUIRED_SCHEMA_VERSION = 17
    }
}
