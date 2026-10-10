package dev.axzy.stock.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.File
import javax.sql.DataSource

/**
 * The integration-test database: **real PostgreSQL, with the real migrations**.
 *
 * It is started once per test process. If Docker is not available, the first test that
 * uses it FAILS with the Testcontainers error; it is not skipped. A skipped test counts as
 * passed in the count, and that has already hidden gaps before (requirement Q5).
 *
 * It exposes two connections, as in production:
 *  - [owner]: the schema owner. Only to migrate and to clean up between tests.
 *  - [api]: a member of the `stock_api` role (migration V11). It is the one the code under test
 *    runs with, so the tests exercise the permissions for real.
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

    /** The API credentials, to start the full application against this database. */
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
        // 16 connections: the concurrency tests open several transactions at once.
        api = pool(API_USER, API_PASSWORD, size = 16)
    }

    /**
     * Leaves the database as freshly migrated. It empties ALL tables except the migration history and
     * the catalogs that the migrations seed (units, the default warehouse and the special
     * locations). The list is computed: a new table is covered without touching this.
     *
     * The last three statements restore the seed, and they are not cosmetic: a test that
     * **moves the default** (K6) leaves the seeded warehouse without the mark and with a location
     * of receiving that references it, and without restoring it the next test's cleanup fails
     * on the foreign key. A test must not be able to break the next one.
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
