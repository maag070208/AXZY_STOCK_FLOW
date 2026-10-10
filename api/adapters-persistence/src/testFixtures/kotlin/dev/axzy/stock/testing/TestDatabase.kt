package dev.axzy.stock.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import javax.sql.DataSource

/**
 * La base de datos de las pruebas de integración: **PostgreSQL real, con las migraciones reales**.
 *
 * Se levanta una vez por proceso de pruebas. Si Docker no está disponible, la primera prueba que
 * la use FALLA con el error de Testcontainers; no se salta. Una prueba saltada cuenta como
 * aprobada en el recuento y eso ya ocultó huecos antes (requisito Q5).
 *
 * Expone dos conexiones, como en producción:
 *  - [owner]: el dueño del esquema. Solo para migrar y para limpiar entre pruebas.
 *  - [api]: un usuario miembro del rol `stock_api` (migración V11). Es con el que corre el código
 *    bajo prueba, así que las pruebas ejercen los permisos de verdad.
 */
object TestDatabase {

    private const val API_USER = "stock_test_app"
    private const val API_PASSWORD = "solo-para-pruebas-locales"

    private val container = PostgreSQLContainer("postgres:16")
        .withDatabaseName("stock")
        .withUsername("stock_owner")
        .withPassword("solo-para-pruebas-locales")

    val owner: DataSource
    val api: DataSource

    /** Las credenciales de la API, para arrancar la aplicación completa contra esta base. */
    val jdbcUrl: String get() = container.jdbcUrl
    const val apiUsername: String = API_USER
    const val apiPassword: String = API_PASSWORD

    init {
        container.start()
        owner = pool(container.username, container.password, size = 4)

        val dbDir = File(
            System.getProperty("stock.db.dir")
                ?: error("Falta la propiedad `stock.db.dir`: la ruta de la carpeta db/ del repositorio."),
        )
        Flyway.configure()
            .dataSource(owner)
            .locations(
                "filesystem:${File(dbDir, "migrations").absolutePath}",
                "filesystem:${File(dbDir, "callbacks").absolutePath}",
            )
            .validateOnMigrate(true)
            .baselineOnMigrate(false)
            .load()
            .migrate()

        JdbcClient.create(owner)
            .sql("CREATE ROLE $API_USER LOGIN PASSWORD '$API_PASSWORD' IN ROLE stock_api")
            .update()
        // 16 conexiones: las pruebas de concurrencia abren varias transacciones a la vez.
        api = pool(API_USER, API_PASSWORD, size = 16)
    }

    /**
     * Deja la base como recién migrada. Vacía TODAS las tablas menos el historial de migraciones y
     * los catálogos que siembran las migraciones (unidades, la bodega por defecto y las ubicaciones
     * especiales). La lista se calcula: una tabla nueva queda cubierta sin tocar esto.
     *
     * Las tres últimas sentencias restauran la semilla, y no son cosméticas: una prueba que
     * **mueve la predeterminada** (K6) deja a la bodega sembrada sin la marca y con una ubicación
     * de recepción que la referencia, y sin restaurarla la limpieza de la prueba siguiente falla
     * por la clave foránea. Una prueba no debe poder romper a la siguiente.
     */
    fun reset() {
        JdbcClient.create(owner).sql(
            """
            DO $$
            DECLARE
                tablas TEXT;
            BEGIN
                SELECT string_agg(format('%I', c.relname), ', ')
                INTO tablas
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r', 'p')
                  AND NOT c.relispartition
                  AND c.relname NOT IN ('flyway_schema_history', 'units_of_measure', 'warehouses', 'locations');
                EXECUTE 'TRUNCATE TABLE ' || tablas || ' RESTART IDENTITY CASCADE';
                DELETE FROM locations WHERE kind = 'SHELF';
                DELETE FROM locations
                WHERE warehouse_id NOT IN (SELECT id FROM warehouses WHERE code = 'MAIN');
                DELETE FROM warehouses WHERE code <> 'MAIN';
                UPDATE warehouses SET is_default = TRUE, active = TRUE WHERE code = 'MAIN';
            END $$;
            """.trimIndent(),
        ).update()
    }

    private fun pool(username: String, password: String, size: Int): DataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                this.username = username
                this.password = password
                maximumPoolSize = size
                poolName = "pruebas-$username"
            },
        )
}
