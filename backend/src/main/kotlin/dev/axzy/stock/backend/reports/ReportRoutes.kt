package dev.axzy.stock.backend.reports

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.security.AllowAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.LocalDate
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DE REPORTES (§9)
 *
 * El permiso es `report.view` en los tres listados y `report.export` en la
 * exportación, **que es el mismo que el guard de la pantalla `/reports`**. Esa
 * coincidencia es obligatoria: con permiso distinto, un usuario entra a la
 * pantalla y recibe un `403` al cargar — el desajuste que ya costó una corrección
 * en la reconciliación (§5.4 de `docs/historico/ARQUITECTURA.md`).
 * ═══════════════════════════════════════════════════════════════════════════
 */
fun Route.reportRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker = DenyAllReports,
) {
    val service = ReportService(dataSource)

    route("/v1/reports") {

        // ── Existencias por producto y ubicación ────────────────────────────
        get("/inventory") {
            call.requireReportPermission(permissionChecker, "report.view")

            val page = call.reportPage()
            val warehouseCode = call.request.queryParameters["warehouseCode"]
            val productCode = call.request.queryParameters["productCode"]
            // Por defecto `true`: un reporte de existencias que liste miles de
            // filas a 0.000 no lo lee nadie. El contrato del listado de inventario
            // usa el mismo valor por defecto.
            val onlyWithStock = call.request.queryParameters["onlyWithStock"]?.toBoolean() ?: true

            val items = service.inventory(
                warehouseCode = warehouseCode,
                productCode = productCode,
                onlyWithStock = onlyWithStock,
                offset = page.offset,
                limit = page.size,
            )
            val total = service.countInventory(warehouseCode, productCode, onlyWithStock)
            val totales = service.inventoryTotals(warehouseCode, productCode, onlyWithStock)

            call.respond(
                HttpStatusCode.OK,
                InventoryReportResponse(
                    items = items,
                    totals = totales,
                    page = page.page,
                    size = page.size,
                    totalItems = total,
                    totalPages = if (page.size == 0) 0 else ((total + page.size - 1) / page.size).toInt(),
                ),
            )
        }

        // ── Resumen de movimientos ──────────────────────────────────────────
        get("/movements-summary") {
            call.requireReportPermission(permissionChecker, "report.view")

            val desde = parseOperationalDate(call.request.queryParameters["from"], "from")
            val hasta = parseOperationalDate(call.request.queryParameters["to"], "to")

            if (hasta.isBefore(desde)) {
                throw BadRequestException(
                    code = "INVALID_FILTER_RANGE",
                    message = "El parámetro 'to' ($hasta) no puede ser anterior a 'from' ($desde).",
                    details = mapOf("from" to desde.toString(), "to" to hasta.toString()),
                )
            }

            val groupBy = call.request.queryParameters["groupBy"]?.trim()?.lowercase() ?: "day"
            if (groupBy !in ReportService.GROUP_BY_ALLOWED) {
                // El contrato exige decir los admitidos en `details.allowed`: sin
                // eso el usuario prueba valores al azar.
                throw BadRequestException(
                    code = "INVALID_PARAMETER",
                    message = "El parámetro 'groupBy' no admite el valor '$groupBy'.",
                    details = mapOf(
                        "groupBy" to groupBy,
                        "allowed" to ReportService.GROUP_BY_ALLOWED.joinToString(", "),
                    ),
                )
            }

            val (totales, items) = service.movementsSummary(desde, hasta, groupBy)

            call.respond(
                HttpStatusCode.OK,
                MovementsSummaryResponse(
                    from = desde.toString(),
                    to = hasta.toString(),
                    groupBy = groupBy,
                    totals = totales,
                    items = items,
                ),
            )
        }

        // ── Exportación del kardex ──────────────────────────────────────────
        get("/kardex/export") {
            // `report.export` y no `report.view`: exportar saca los datos del
            // sistema en un archivo que se puede reenviar, y el catálogo de
            // permisos los separa a propósito (§11).
            call.requireReportPermission(permissionChecker, "report.export")

            val formato = (call.request.queryParameters["format"] ?: "").trim().lowercase()
            if (formato !in ReportService.EXPORT_FORMATS) {
                throw BadRequestException(
                    code = "INVALID_PARAMETER",
                    message = "El parámetro 'format' debe ser uno de: " +
                        ReportService.EXPORT_FORMATS.joinToString(", ") + ". Se recibió '$formato'.",
                    details = mapOf(
                        "format" to formato,
                        "allowed" to ReportService.EXPORT_FORMATS.joinToString(", "),
                    ),
                )
            }

            // **Los filtros son los MISMOS que los del listado de kardex**: si el
            // usuario ve 200 filas filtradas y exporta, tiene que recibir esas 200.
            // Aceptar filtros distintos aquí es el defecto clásico: el archivo y la
            // pantalla dicen cosas diferentes y el usuario no sabe cuál creer.
            val tabla = service.kardexExport(
                productCode = call.request.queryParameters["productCode"],
                type = call.request.queryParameters["moveType"],
                from = call.request.queryParameters["from"]?.let { parseOperationalDate(it, "from") },
                until = call.request.queryParameters["to"]?.let { parseOperationalDate(it, "to") },
            )

            val (bytes, contentType) = when (formato) {
                "csv" -> tabla.toCsv() to ContentType.parse("text/csv; charset=utf-8")
                else -> tabla.toXlsx("Kardex") to ContentType.parse(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                )
            }

            val fileName = "kardex-${LocalDate.now()}.$formato"
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileName,
                ).toString(),
            )
            call.respondBytes(bytes, contentType, HttpStatusCode.OK)
        }
    }
}

/**
 * Verificador que deniega todo, para el valor por defecto de las rutas.
 *
 * Existe para que olvidarse de pasar el verificador **no deje las rutas abiertas**:
 * el defecto tiene que ser el que falla cerrado, no el que permite.
 */
private object DenyAllReports : PermissionChecker {
    override suspend fun check(call: ApplicationCall, permission: String): PermissionDecision =
        PermissionDecision.Denied(
            code = "UNAUTHENTICATED",
            message = "No hay verificador de permisos configurado para los reportes.",
        )
}

/**
 * Exige un permiso traduciendo la denegación al estado del contrato.
 *
 * `UNAUTHENTICATED` es `401` y el resto `403` (§1.2). Distinguirlos importa: ante
 * un `401` el cliente manda a iniciar sesión y ante un `403`, a pedir un permiso.
 */
private suspend fun ApplicationCall.requireReportPermission(
    verifier: PermissionChecker,
    permission: String,
) {
    when (val decision = verifier.check(this, permission)) {
        is PermissionDecision.Permitted -> Unit
        is PermissionDecision.Denied ->
            if (decision.code == "UNAUTHENTICATED") {
                throw UnauthorizedException(decision.code, decision.message)
            } else {
                throw ForbiddenException(decision.code, decision.message)
            }
    }
}

/**
 * Paginación del reporte, con el tope del contrato.
 *
 * Se declara aquí y no se reutiliza la de `catalog/` porque esa vive en la feature
 * del catálogo y **un reporte no debería depender del catálogo para paginar**
 * (fase 1 del plan de migración: `Pagination` sale de `catalog/` a un sitio
 * compartido). Mientras eso no pase, esta es la copia mínima, y es deliberado que
 * sea mínima para que el día que se comparta se borre sin pensar.
 */
private fun ApplicationCall.reportPage(): ReportPage {
    val brutoPagina = request.queryParameters["page"]
    val brutoTamano = request.queryParameters["size"]

    val pagina = brutoPagina?.trim()?.toIntOrNull()
        ?: if (brutoPagina.isNullOrBlank()) 0 else throw badPagination("page", brutoPagina)
    val tamano = brutoTamano?.trim()?.toIntOrNull()
        ?: if (brutoTamano.isNullOrBlank()) 50 else throw badPagination("size", brutoTamano)

    if (pagina < 0) throw badPagination("page", brutoPagina)
    if (tamano < 1 || tamano > 200) {
        // Se RECHAZA en lugar de recortar: recortar hace que el cliente crea que
        // tiene todos los datos (regla de §1.4, y ya produjo dos fallos reales).
        throw badPagination("size", brutoTamano)
    }

    return ReportPage(pagina, tamano)
}

private data class ReportPage(val page: Int, val size: Int) {
    val offset: Int get() = page * size
}

private fun badPagination(parametro: String, valor: String?) = BadRequestException(
    code = "INVALID_PAGINATION",
    message = "El parámetro '$parametro' no es válido. Se recibió '$valor'. " +
        "`page` empieza en 0 y `size` admite de 1 a 200.",
    details = mapOf(parametro to (valor ?: "")),
)
