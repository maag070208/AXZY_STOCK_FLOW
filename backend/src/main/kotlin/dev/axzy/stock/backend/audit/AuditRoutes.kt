package dev.axzy.stock.backend.audit

import dev.axzy.stock.backend.audit.AuditFilters
import dev.axzy.stock.backend.audit.AuditQueryService
import dev.axzy.stock.backend.catalog.Page
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.backend.catalog.aRespuesta
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DE AUDITORÍA — `CONTRATO_API.md` §11, `PLAN_AUDITORIA.md` §8
 *
 * Un solo endpoint por ahora: `GET /v1/audit/log`, la bitácora general con sus
 * filtros y su paginación resuelta en la base.
 *
 * ── ES DE SOLO LECTURA, Y ESO SE EXPRESA NO ESCRIBIENDO RUTAS ──────────────
 *
 * No hay `PUT`, ni `DELETE`, ni `PATCH`, ni `POST` en este archivo. Podría
 * parecer que falta trabajo; es al contrario. La tabla es inmutable por
 * disparadores (`audit_log_attach_immutability`) y esos `UPDATE`/`DELETE` **fallan
 * a propósito**, así que una ruta que los ofreciera sería una promesa que la base
 * rompe con un error de restricción. Un registro equivocado se corrige añadiendo
 * otro que lo compensa, y esa corrección también queda auditada.
 *
 * ── EL PERMISO NO ES DECORATIVO ────────────────────────────────────────────
 *
 * `audit.view`, con alcance `ALL` (§11). La auditoría contiene quién hizo qué y
 * desde qué IP: es el dato más sensible del sistema. El rol `AUDITOR` lo tiene
 * (es uno de sus 20 permisos) y ese es exactamente el rol para el que existe este
 * endpoint. `CONTRATO_API.md` §11 recuerda que el legacy tenía siete permisos
 * sembrados que ninguna ruta exigía; aquí se exige el que corresponde.
 * ═══════════════════════════════════════════════════════════════════════════
 */
fun Route.auditRoutes(
    servicio: AuditQueryService,
    permissionChecker: PermissionChecker,
) {

    get("/v1/audit/log") {
        when (val decision = permissionChecker.comprobar(call, "audit.view")) {
            is PermissionDecision.Permitido -> Unit
            is PermissionDecision.Denegado -> throw ForbiddenException(decision.code, decision.message)
        }

        // **El orden por defecto es DESCENDENTE, y `PageRequest` no lo hace solo.**
        // Su convención es ascendente cuando no se le pasa `sort`, y un listado
        // genérico ascendente es lo razonable. Un registro de auditoría no: se lee
        // de lo último hacia atrás, porque la pregunta que se hace casi siempre es
        // «¿qué acaba de pasar?». En lugar de duplicar la paginación para cambiar
        // una dirección, se le pasa el orden por defecto explícito; así el cliente
        // que escribe `sort=occurredAt` obtiene lo que pidió (ascendente) y el que
        // no escribe nada obtiene lo que espera (lo más reciente primero).
        val page = PageRequest.analizar(
            page = call.request.queryParameters["page"],
            size = call.request.queryParameters["size"],
            sort = call.request.queryParameters["sort"] ?: "occurredAt,desc",
            camposOrdenables = CAMPOS_ORDENABLES,
            ordenPorDefecto = "occurredAt",
        )

        val filtros = call.filtrosDeAuditoria()

        // El listado y el conteo comparten `filtros`: no hay forma de que el
        // sobre diga «1.064 registros» y los items sean cero, que es el error
        // que ya se cometió en el kardex.
        val items = servicio.listar(
            filtros = filtros,
            descendente = !page.ascendente,
            offset = page.offset.toInt(),
            limite = page.size,
        )
        // **El total se CUENTA, no se adivina con `items.size`.** El bug del
        // inventario hacía que el sobre dijera «50 resultados» siempre.
        val total = servicio.contar(filtros)

        call.respond(
            HttpStatusCode.OK,
            Page(items, page.page, page.size, total).aRespuesta { it },
        )
    }
}

/**
 * Los campos por los que se puede ordenar, declarados por endpoint como exige el
 * contrato. Solo la fecha: ordenar por actor o por acción sobre un ledger de
 * miles de filas no responde a ninguna pregunta de auditoría y multiplicaría los
 * índices necesarios.
 */
private val CAMPOS_ORDENABLES = setOf("occurredAt")

/**
 * Lee y valida los filtros de la consulta.
 *
 * **Un filtro mal escrito se rechaza con 400, no se ignora.** Ignorarlo es la
 * peor de las opciones: el cliente pide «las denegaciones de ayer» y recibe otra
 * cosa sin saberlo, y como el conteo y el listado comparten los filtros, la
 * respuesta es coherente consigo misma y por tanto creíble. Un 400 dice la
 * verdad a la primera.
 *
 * Todos los filtros son opcionales (`null` = no se pidió) y ninguno es
 * obligatorio: la bitácora se puede abrir entera y podar a mano.
 */
private fun ApplicationCall.filtrosDeAuditoria(): AuditFilters {
    val parametros = request.queryParameters

    val desde = parametros.textoOpcional("from")?.let { instante(it, esFinDeDia = false) }
    val hasta = parametros.textoOpcional("to")?.let { instante(it, esFinDeDia = true) }

    // Un rango invertido devuelve cero filas siempre. Es mejor decirlo que
    // devolver una lista vacía que el usuario interpretará como «no hay nada
    // registrado» cuando el error está en su petición.
    if (desde != null && hasta != null && desde.isAfter(hasta)) {
        throw BadRequestException(
            code = "INVALID_FILTER_RANGE",
            message = "El parámetro 'from' es posterior a 'to', así que el rango no " +
                "contiene ningún instante. Un rango invertido no devuelve nada y " +
                "parecería que no hay registros.",
            details = mapOf("from" to parametros["from"].orEmpty(), "to" to parametros["to"].orEmpty()),
        )
    }

    return AuditFilters(
        desde = desde,
        hasta = hasta,
        actorUserId = parametros.numeroOpcional("userId"),
        entityType = parametros.textoOpcional("entity"),
        entityId = parametros.textoOpcional("entityId"),
        action = parametros.textoOpcional("action"),
        correlationId = parametros.uuidOpcional("correlationId"),
        decision = parametros.enumOpcional("decision", AuditQueryService.DECISIONES),
        result = parametros.enumOpcional("result", AuditQueryService.RESULTADOS),
        actionLevel = parametros.enumOpcional("actionLevel", AuditQueryService.NIVELES),
        channel = parametros.enumOpcional("channel", AuditQueryService.CANALES),
    )
}

/** Un parámetro de la URL, ausente o vacío = no se pidió. */
private fun io.ktor.http.Parameters.textoOpcional(nombre: String): String? =
    this[nombre]?.trim()?.takeIf { it.isNotEmpty() }

/** Entero opcional; si viene y no es un número, es un 400 y no un filtro ignorado. */
private fun io.ktor.http.Parameters.numeroOpcional(nombre: String): Long? {
    val crudo = textoOpcional(nombre) ?: return null
    return crudo.toLongOrNull() ?: throw BadRequestException(
        code = "INVALID_FILTER",
        message = "El parámetro '$nombre' debe ser un número entero. Se recibió '$crudo'.",
        details = mapOf(nombre to crudo),
    )
}

/**
 * UUID opcional, validado en Kotlin.
 *
 * **No se deja al `CAST` de PostgreSQL**: `?::uuid` con un texto que no es UUID
 * lanza una excepción que saldría como **500 INTERNAL_ERROR**, cuando el error es
 * de quien llama y merece un **400**. Es exactamente el tropiezo que ya tuvimos
 * con la cabecera `Idempotency-Key` y no se repite aquí.
 */
private fun io.ktor.http.Parameters.uuidOpcional(nombre: String): UUID? {
    val crudo = textoOpcional(nombre) ?: return null
    return try {
        UUID.fromString(crudo)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException(
            code = "INVALID_FILTER",
            message = "El parámetro '$nombre' debe ser un UUID. Se recibió '$crudo'.",
            details = mapOf(nombre to crudo),
        )
    }
}

/**
 * Un valor de los enums de PostgreSQL (`audit_channel`, `audit_level`,
 * `audit_result`, `audit_decision`), validado contra la lista real.
 *
 * Sin esta comprobación, `?channel=TELEPATIA` llegaría al `?::audit_channel` y
 * PostgreSQL respondería con un error de conversión: un **500** por un dato que
 * el cliente escribió mal. El 400 además dice cuáles son los valores válidos, que
 * es lo que el cliente necesita para corregir.
 */
private fun io.ktor.http.Parameters.enumOpcional(nombre: String, permitidos: Set<String>): String? {
    val crudo = textoOpcional(nombre) ?: return null
    val valor = crudo.uppercase()
    if (valor !in permitidos) {
        throw BadRequestException(
            code = "INVALID_FILTER",
            message = "El parámetro '$nombre' no admite '$crudo'. Valores válidos: " +
                permitidos.sorted().joinToString(", ") + ".",
            details = mapOf(nombre to crudo),
        )
    }
    return valor
}

/**
 * Un instante a partir de `2026-11-15T08:30:00Z` o de `2026-11-15`.
 *
 * Se admiten las dos formas porque las dos se usan: la pantalla manda fechas
 * —«desde el 1 de noviembre»— y un cliente que sigue un `correlationId` manda
 * instantes. Una fecha suelta se interpreta como el día completo **en UTC**, en
 * coherencia con `business_date` y con el kardex, que ya lo hace así; el
 * `occurred_at` ya es `TIMESTAMPTZ` y no depende de la zona del servidor.
 *
 * Si no es ninguna de las dos formas, se rechaza: devolver `null` —ignorar el
 * filtro— haría que el listado y el conteo coincidieran entre sí pero no con lo
 * que pidió el cliente.
 */
private fun instante(crudo: String, esFinDeDia: Boolean): Instant {
    // Primero instante completo: `Instant.parse` exige zona (`Z` o `+02:00`) y
    // así no se confunde una fecha suelta con un instante a medianoche.
    runCatching { Instant.parse(crudo) }.getOrNull()?.let { return it }

    val fecha = runCatching { LocalDate.parse(crudo) }.getOrNull()
        ?: throw BadRequestException(
            code = "INVALID_FILTER_DATE",
            message = "El valor '$crudo' no es una fecha ISO ('2026-11-15') ni un instante " +
                "ISO-8601 ('2026-11-15T08:30:00Z').",
            details = mapOf("valor" to crudo),
        )

    return if (esFinDeDia) {
        // El final del día incluye el último nanosegundo: con `23:59:59` se
        // perderían los registros de ese último segundo, y en un ledger que
        // registra accesos eso es un hueco silencioso.
        fecha.atTime(23, 59, 59, 999_999_999).toInstant(ZoneOffset.UTC)
    } else {
        fecha.atStartOfDay(ZoneOffset.UTC).toInstant()
    }
}
