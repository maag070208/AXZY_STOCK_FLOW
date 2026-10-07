package dev.axzy.stock.backend.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONFIGURACIÓN DE LA BASE DE DATOS
 *
 * **La configuración se lee del entorno y falla si falta.** No hay valores por
 * defecto para credenciales: un despliegue que arranca con una contraseña por
 * defecto es un despliegue vulnerable que nadie nota. Es la corrección del
 * hallazgo S5 del legacy, donde las credenciales estaban en el repositorio.
 *
 * Ver `infra/.env.example` para las variables que hay que definir.
 * ═══════════════════════════════════════════════════════════════════════════
 */
data class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maxPoolSize: Int,
    val runMigrations: Boolean,
) {
    companion object {

        /**
         * Lee la configuración del entorno.
         *
         * @throws IllegalStateException si falta una variable obligatoria. Se
         *   prefiere fallar al arrancar que arrancar con una suposición: si el
         *   backend se conecta a la base equivocada porque faltaba una variable,
         *   el daño es peor que no arrancar.
         */
        fun fromEnvironment(): DatabaseConfig {
            val host = requerido("STOCK_DB_HOST")
            val port = System.getenv("STOCK_DB_PORT") ?: "5432"
            val name = requerido("STOCK_DB_NAME")
            val user = requerido("STOCK_DB_USER")
            val password = requerido("STOCK_DB_PASSWORD")

            return DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://$host:$port/$name",
                username = user,
                password = password,
                maxPoolSize = System.getenv("STOCK_DB_POOL_SIZE")?.toIntOrNull() ?: 10,
                // Las migraciones se ejecutan al arrancar SOLO si se pide
                // explícitamente. En producción se aplican como paso de
                // despliegue: un arranque que migra solo puede sorprender.
                runMigrations = System.getenv("STOCK_DB_RUN_MIGRATIONS")?.toBoolean() ?: false,
            )
        }

        private fun requerido(nombre: String): String =
            System.getenv(nombre)?.takeIf { it.isNotBlank() }
                ?: error(
                    "Falta la variable de entorno $nombre. " +
                        "Cópiala de infra/.env.example. No hay valor por defecto: " +
                        "un despliegue con credenciales por defecto es un despliegue " +
                        "vulnerable que nadie nota."
                )
    }
}

/** El logger de la aplicación. */
val logger = LoggerFactory.getLogger("dev.axzy.stock.backend")

/**
 * Construye el pool de conexiones.
 *
 * HikariCP con `autoCommit = false` por defecto, para que cada operación que
 * mueva inventario declare su transacción. Es la corrección de un defecto del
 * legacy: allí una recepción hacía varias escrituras sin transacción, así que un
 * fallo a mitad dejaba el kardex y el stock descuadrados de forma permanente.
 */
fun buildDataSource(config: DatabaseConfig): DataSource {
    val hikari = HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.username
        password = config.password
        maximumPoolSize = config.maxPoolSize
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        poolName = "stock-pool"
        // Verificación temprana: si la base no está, el arranque falla con un
        // mensaje claro en lugar de fallar en la primera petición del usuario.
        connectionTimeout = 10_000
        validationTimeout = 5_000
        initializationFailTimeout = 10_000
    }
    logger.info("Pool de conexiones creado hacia {}", config.jdbcUrl)
    return HikariDataSource(hikari)
}

/**
 * Aplica las migraciones de Flyway.
 *
 * `baselineOnMigrate` está desactivado a propósito: una base sin historial de
 * migraciones es una base que no se reconoce, y hay que decidirlo a mano. El
 * legacy tenía 36 migraciones en una carpeta que Prisma no leía, más una que
 * recreaba el esquema: su historial **no reconstruía la base**.
 */
fun migrate(dataSource: DataSource) {
    val resultado = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .load()
        .migrate()

    logger.info(
        "Migraciones aplicadas: {} (esquema en versión {})",
        resultado.migrationsExecuted,
        resultado.targetSchemaVersion ?: "sin cambios",
    )
}
