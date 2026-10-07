package dev.axzy.stock.backend

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.config.migrate
import dev.axzy.stock.backend.error.ErrorResponse
import dev.axzy.stock.backend.error.StockException
import dev.axzy.stock.backend.routes.catalogRoutes
import dev.axzy.stock.backend.security.DenyAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.routes.healthRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PUNTO DE ENTRADA DEL BACKEND
 *
 * Ktor 3 + Netty. La configuración viene del entorno (§ DatabaseConfig) y nunca
 * del código, para no repetir el hallazgo S5 del legacy.
 * ═══════════════════════════════════════════════════════════════════════════
 */
fun main() {
    val config = DatabaseConfig.fromEnvironment()

    if (config.runMigrations) {
        // Se migra antes de crear el pool de la aplicación para que las
        // conexiones no vean un esquema a medio aplicar.
        buildDataSource(config).use { pool ->
            migrate(pool)
        }
    }

    val puerto = System.getenv("STOCK_PORT")?.toIntOrNull() ?: 8080

    logger.info("Arrancando el backend en el puerto {}", puerto)

    embeddedServer(Netty, port = puerto, host = "0.0.0.0") {
        module(buildDataSource(config))
    }.start(wait = true)
}

/**
 * El módulo de la aplicación.
 *
 * Se separa de [main] y acepta el [DataSource] como parámetro para que los tests
 * puedan instalar el mismo módulo con un pool distinto, sin arrancar un servidor
 * real. Es lo que permite probar el comportamiento HTTP en milisegundos.
 */
fun Application.module(
    dataSource: DataSource,
    /**
     * El verificador de permisos.
     *
     * **Es un parámetro y no una constante, y eso corrige un defecto real.**
     * Estaba fijo a [DenyAllPermissionChecker], así que los tests de las rutas
     * montaban `module(pool)` **y además** su propio `routing { catalogRoutes(...) }`
     * con un verificador que permite todo. Al conectar el catálogo en este
     * módulo, las rutas quedaron registradas **dos veces** y Ktor resolvía la
     * primera —la del verificador que deniega— así que 49 tests empezaron a
     * recibir 401.
     *
     * La causa de fondo no era el duplicado: era que **los tests no probaban la
     * configuración que se despliega**. Ahora el módulo recibe el verificador, se
     * registra una sola vez, y un test puede ejercitar el camino real con un
     * verificador de prueba.
     */
    permissionChecker: PermissionChecker = DenyAllPermissionChecker,
) {

    // ── Negociación de contenido ────────────────────────────────────────────
    install(ContentNegotiation) {
        json(
            Json {
                // `ignoreUnknownKeys`: el cliente puede ir por delante del
                // servidor durante un despliegue. Sin esto, un campo nuevo del
                // móvil rompe el backend viejo.
                ignoreUnknownKeys = true
                // `explicitNulls = false`: no se envían nulos en el JSON. Un
                // campo ausente y un campo nulo significan lo mismo para el
                // cliente, y omitirlos reduce el tamaño de las respuestas.
                explicitNulls = false
                encodeDefaults = true
                prettyPrint = false
            }
        )
    }

    // ── Registro de peticiones ──────────────────────────────────────────────
    install(CallLogging) {
        level = Level.INFO
        // El formato incluye el método, la ruta y el estado. El `requestId` se
        // añadirá en la Fase 2, cuando exista correlación de auditoría.
        format { call ->
            val estado = call.response.status()?.value ?: "-"
            "${call.request.httpMethod.value} ${call.request.path()} → $estado"
        }
    }

    // ── Manejo de errores ───────────────────────────────────────────────────
    install(StatusPages) {
        exception<StockException> { call, causa ->
            // Un error de dominio tiene su propio código HTTP y su propio
            // código de error. Se responde con un contrato estable para que el
            // cliente pueda reaccionar sin interpretar el texto.
            call.respond(
                HttpStatusCode.fromValue(causa.httpStatus),
                ErrorResponse(
                    code = causa.code,
                    message = causa.message,
                    details = causa.details,
                ),
            )
        }

        exception<Throwable> { call, causa ->
            // Un error inesperado NO se filtra al cliente: el mensaje interno
            // puede contener detalles de esquema o de conexión. Se registra
            // completo y se responde genérico.
            //
            // En el legacy los errores de Prisma llegaban al cliente con el SQL
            // y los nombres de tabla. Eso es información para un atacante.
            logger.error("Error no controlado en ${call.request.path()}", causa)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    code = "INTERNAL_ERROR",
                    message = "Error interno. El incidente quedó registrado.",
                ),
            )
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(
                    code = "NOT_FOUND",
                    message = "El recurso solicitado no existe.",
                ),
            )
        }
    }

    routing {
        healthRoutes(dataSource)

        // ── Catálogo y ubicaciones ──────────────────────────────────────────
        // El verificador por defecto DENIEGA TODO, así que estas rutas responden
        // 401 hasta que se conecte la identidad. Es la postura correcta: una
        // ruta sin permiso concedido no debe quedar abierta mientras tanto.
        catalogRoutes(dataSource, permissionChecker)
    }
}

/** Cierra el pool al apagar, para no dejar conexiones colgadas. */
private fun DataSource.use(bloque: (DataSource) -> Unit) {
    try {
        bloque(this)
    } finally {
        (this as? AutoCloseable)?.close()
    }
}
