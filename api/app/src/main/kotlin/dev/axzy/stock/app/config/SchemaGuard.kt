package dev.axzy.stock.app.config

import dev.axzy.stock.application.health.DatabaseProbe
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The schema version the API started with. Requesting it as a dependency guarantees the check has already run. */
data class SchemaVersion(val applied: Int)

/**
 * **The API does not migrate: it checks.** The migrations are applied by the `migrate` service (ADR-0016). If the
 * database is behind what this code needs, the API refuses to start and says what is missing.
 *
 * The case this prevents already happened: a development database was left without a migration for
 * weeks, and the API started anyway and answered 500 on the routes that depended on it.
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
         * The last migration in `db/migrations` that this code considers applied. When a migration is added
         * it is raised here; a test checks that it matches the files.
         */
        const val REQUIRED_SCHEMA_VERSION = 17
    }
}
