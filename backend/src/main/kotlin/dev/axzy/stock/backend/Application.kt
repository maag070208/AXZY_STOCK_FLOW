package dev.axzy.stock.backend

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.config.migrate
import dev.axzy.stock.backend.error.ErrorResponse
import dev.axzy.stock.backend.error.StockException
import dev.axzy.stock.backend.identity.aAccessUser
import dev.axzy.stock.backend.identity.authRoutes
import dev.axzy.stock.backend.identity.claimsDeToken
import dev.axzy.stock.backend.audit.AuditQueryService
import dev.axzy.stock.backend.inventory.InventoryService
import dev.axzy.stock.backend.audit.auditRoutes
import dev.axzy.stock.backend.catalog.catalogRoutes
import dev.axzy.stock.backend.receiving.ReceivingService
import dev.axzy.stock.backend.inventory.inventoryRoutes
import dev.axzy.stock.backend.receiving.receivingRoutes
import dev.axzy.stock.backend.security.DenyAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.health.healthRoutes
import dev.axzy.stock.backend.identity.identityAdminRoutes
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

    // El servicio de tokens exige STOCK_JWT_SECRET y FALLA si falta: un secreto
    // por defecto permitiria a cualquiera firmar tokens validos.
    val tokens = dev.axzy.stock.backend.identity.TokenService(
        dev.axzy.stock.backend.identity.TokenConfig.fromEnvironment(),
    )
    val hasher = dev.axzy.stock.backend.identity.NativeArgon2PasswordHasher()

    // ── Sembrado inicial ────────────────────────────────────────────────────
    // Se ejecuta si se pide, y es idempotente: crear los roles, los 82 permisos y
    // el administrador no se repite ni falla si ya existen.
    //
    // **Sin esto el backend arranca y no se puede entrar**: la pantalla de acceso
    // no tiene con qué validar.
    if (System.getenv("STOCK_SEED")?.toBoolean() == true) {
        buildDataSource(config).use { pool ->
            val resultado = dev.axzy.stock.backend.identity.IdentitySeeder(pool).seed(
                adminUsername = System.getenv("STOCK_ADMIN_USER") ?: "admin",
                adminPassword = System.getenv("STOCK_ADMIN_PASSWORD"),
            )
            logger.info(
                "Sembrado: {} roles, {} permisos, administrador {}",
                resultado.rolesCreated,
                resultado.permissionsCreated,
                if (resultado.adminCreated) "CREADO" else "ya existía",
            )
            // La contraseña se imprime UNA SOLA VEZ, y solo si se acaba de crear.
            // No queda en ningún registro ni se vuelve a mostrar.
            resultado.adminPassword?.let { contrasena ->
                logger.warn(
                    "════════════════════════════════════════════════════════════\n" +
                        "  CONTRASEÑA DEL ADMINISTRADOR (se muestra UNA sola vez)\n" +
                        "    usuario:    {}\n" +
                        "    contraseña: {}\n" +
                        "  Cámbiala al entrar. No se guarda en ningún sitio.\n" +
                        "════════════════════════════════════════════════════════════",
                    resultado.adminUsername,
                    contrasena,
                )
            }
        }
    }

    logger.info("Arrancando el backend en el puerto {}", puerto)

    // ── El verificador de permisos, conectado a la identidad ────────────────
    // Lee el token de la cabecera, lo traduce al `AccessUser` del dominio y deja
    // que el MOTOR de acceso decida. El motor tiene 43 tests y es el mismo que
    // usa el móvil: aquí no se reimplementa la autorización.
    val pool = buildDataSource(config)
    val verificador = dev.axzy.stock.backend.security.AccessEnginePermissionChecker(
        engine = dev.axzy.stock.domain.access.AccessEngine(),
        usuarioActual = { call ->
            val claims = call.claimsDeToken(tokens)
            claims?.let { it.aAccessUser() }
        },
    )

    embeddedServer(Netty, port = puerto, host = "0.0.0.0") {
        module(pool, tokens, hasher, verificador)
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
    tokenService: dev.axzy.stock.backend.identity.TokenService? = null,
    passwordHasher: dev.axzy.stock.domain.identity.PasswordHasher? = null,
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
    /**
     * El verificador de permisos.
     *
     * En producción se pasa uno conectado a la identidad; el valor por defecto
     * **deniega todo**, que es la postura correcta: una ruta sin permiso concedido
     * no debe quedar abierta.
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

        // ── Inventario ──────────────────────────────────────────────────────
        // Se conecta SIEMPRE, como el catálogo: el verificador por defecto deniega
        // todo, así que las rutas responden 401 y **nunca 404**. Una ruta sin
        // permiso concedido no debe quedar abierta, pero tampoco debe faltar: un
        // 404 esconde que la funcionalidad existe y solo está sin autorizar.
        inventoryRoutes(InventoryService(dataSource), permissionChecker)

        // ── Recepción ───────────────────────────────────────────────────────
        // La regla B1 vive aquí: una línea con discrepancia no se puede ubicar
        // hasta que un supervisor apruebe la factura.
        receivingRoutes(ReceivingService(dataSource), permissionChecker)

        // ── Administración de identidad ─────────────────────────────────────
        // Personas, roles y catálogo de permisos. Se conecta siempre, como el
        // catálogo: el verificador por defecto deniega todo, así que las rutas
        // responden 401 y **nunca 404**. Sin conectarlas, las pantallas de
        // administración quedarían vacías con todo el módulo ya escrito.
        identityAdminRoutes(dataSource, permissionChecker)

        // ── Auditoría (SOLO LECTURA) ────────────────────────────────────────
        // Se conecta siempre, como las demás: con el verificador por defecto las
        // rutas responden 401 y **nunca 404**. Un 404 escondería que el endpoint
        // existe y solo está sin autorizar, que es justo la confusión que el
        // test estructural de conexión (`RoutesWiringTest`) existe para evitar.
        //
        // Aquí no hay `POST`, ni `PUT`, ni `DELETE`: el ledger es inmutable por
        // disparadores y ofrecer una escritura sería prometer algo que la base
        // rechaza a propósito.
        auditRoutes(AuditQueryService(dataSource), permissionChecker)

        // ── Autenticación ───────────────────────────────────────────────────
        // Solo se monta si hay servicio de tokens. Sin él, el módulo sigue
        // arrancando y las rutas de auth no existen —que es lo que quieren los
        // tests del catálogo, que no necesitan identidad—.
        if (tokenService != null && passwordHasher != null) {
            authRoutes(
                repositorio = dev.axzy.stock.backend.identity.IdentityRepository(dataSource),
                tokens = tokenService,
                hasher = passwordHasher,
                permissionChecker = permissionChecker,
            )
        }
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
