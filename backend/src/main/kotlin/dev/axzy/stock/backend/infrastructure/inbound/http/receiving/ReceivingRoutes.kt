package dev.axzy.stock.backend.infrastructure.inbound.http.receiving

import dev.axzy.stock.backend.application.receiving.ApproveInvoiceRequest
import dev.axzy.stock.backend.application.receiving.CaptureLineRequest
import dev.axzy.stock.backend.application.receiving.InvoiceCreatedResponse
import dev.axzy.stock.backend.application.receiving.InvoicePageResponse
import dev.axzy.stock.backend.application.receiving.NewInvoiceLine
import dev.axzy.stock.backend.application.receiving.NewInvoiceRequest
import dev.axzy.stock.backend.application.receiving.ReceivingUseCases
import dev.axzy.stock.backend.application.receiving.RejectDiscrepancyRequest
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.pathLong
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.quantityOf
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.requirePermission
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.requireUser
import dev.axzy.stock.backend.infrastructure.inbound.http.shared.statusFilter
import dev.axzy.stock.backend.security.PermissionChecker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDate

/**
 * Rutas de recepción (contrato §5): el flujo completo de una factura.
 *
 * **La aprobación es por factura** (decisión R-86) y por eso solo hay
 * `POST /v1/invoices/{id}/approve`. La variante por línea se eliminó del contrato: aprobar de
 * una en una invita a aprobar lo propio sin darse cuenta.
 */
fun Route.receivingRoutes(
    useCases: ReceivingUseCases,
    permissionChecker: PermissionChecker,
) {
    route("/v1/invoices") {

        get {
            call.requirePermission(permissionChecker, "invoice.view")

            val rawPage = call.request.queryParameters["page"]
            val rawSize = call.request.queryParameters["size"]
            val page = rawPage?.toIntOrNull() ?: 0
            val size = rawSize?.toIntOrNull() ?: 50

            if (page < 0) {
                throw BadRequestException(
                    "INVALID_PAGINATION",
                    "El parámetro 'page' empieza en 0. Se recibió '$rawPage'.",
                )
            }
            // Se RECHAZA en lugar de recortar: recortar hace creer al cliente que tiene todos
            // los datos (§1.4).
            if (size < 1 || size > 200) {
                throw BadRequestException(
                    "INVALID_PAGINATION",
                    "El parámetro 'size' admite de 1 a 200. Se recibió '$rawSize'.",
                )
            }

            val status = call.statusFilter(INVOICE_STATUSES)
            val search = call.request.queryParameters["q"]

            val items = useCases.list(status, search, page * size, size)
            val total = useCases.count(status, search)

            call.respond(
                HttpStatusCode.OK,
                InvoicePageResponse(
                    items = items,
                    page = page,
                    size = size,
                    totalItems = total,
                    totalPages = ((total + size - 1) / size).toInt(),
                ),
            )
        }

        post("/{id}/close") {
            call.requirePermission(permissionChecker, "invoice.close")
            val invoiceId = call.invoiceId()
            // La regla la aplica el caso de uso con el criterio del dominio: la ruta no repite
            // los estados.
            call.respond(HttpStatusCode.OK, useCases.close(invoiceId, call.requireUser()))
        }

        post {
            call.requirePermission(permissionChecker, "invoice.create")
            val body = call.receive<NewInvoiceRequest>()

            if (body.invoiceNumber.isBlank()) {
                throw BadRequestException("FIELD_REQUIRED", "El número de factura es obligatorio.")
            }
            // Una factura sin líneas no sirve para nada y bloquea el flujo: no se puede capturar
            // ni ubicar nada.
            if (body.lines.isEmpty()) {
                throw BadRequestException(
                    "FIELD_REQUIRED",
                    "Una factura necesita al menos una línea. Sin líneas no hay nada que recibir.",
                    mapOf("lines" to "vacío"),
                )
            }

            val date = runCatching { LocalDate.parse(body.receiptDate) }.getOrNull()
                ?: throw BadRequestException(
                    "INVALID_PARAMETER",
                    "La fecha de recepción debe tener el formato AAAA-MM-DD.",
                )

            val lines = body.lines.map {
                NewInvoiceLine(
                    productCode = it.productCode,
                    container = it.container,
                    unitMeasure = it.unitMeasure,
                    quantity = quantityOf(it.quantity),
                    boxQuantity = quantityOf(it.boxQuantity),
                    unitsPerBox = it.unitsPerBox?.let(::quantityOf),
                )
            }
            call.requireUser()

            val id = useCases.createInvoice(
                number = body.invoiceNumber,
                date = date,
                type = body.invoiceType.uppercase(),
                lines = lines,
            )

            call.respond(HttpStatusCode.Created, InvoiceCreatedResponse(id, body.invoiceNumber))
        }

        get("/{id}") {
            call.requirePermission(permissionChecker, "invoice.view")
            call.respond(HttpStatusCode.OK, useCases.invoice(call.invoiceId()))
        }

        // Bandeja de pendientes. **Pantalla nueva: el legacy no la tenía.** Sin ella, una
        // discrepancia se quedaba bloqueada sin que nadie supiera que había que aprobarla.
        get("/pending-approval") {
            call.requirePermission(permissionChecker, "invoice.view")
            call.respond(HttpStatusCode.OK, mapOf("items" to useCases.pendingApprovals()))
        }

        post("/{id}/lines/{lineId}/capture") {
            call.requirePermission(permissionChecker, "invoice.capture")
            val invoiceId = call.invoiceId()
            val lineId = call.pathLong("lineId", "El identificador de línea no es un número.")

            val body = call.receive<CaptureLineRequest>()

            val line = useCases.captureLine(
                invoiceId = invoiceId,
                lineId = lineId,
                quantityPhysical = quantityOf(body.physicalQuantity),
                boxesPhysical = body.physicalBoxQuantity?.let(::quantityOf),
                userId = call.requireUser(),
            )

            call.respond(HttpStatusCode.OK, line)
        }

        // LA REGLA B1: se aprueba la factura, no la línea.
        post("/{id}/approve") {
            call.requirePermission(permissionChecker, "invoice.approve")
            val invoiceId = call.invoiceId()

            val body = call.receive<ApproveInvoiceRequest>()

            val detail = useCases.approveInvoice(
                invoiceId = invoiceId,
                reason = body.adjustmentReason,
                note = body.note,
                approverId = call.requireUser(),
            )

            call.respond(HttpStatusCode.OK, detail)
        }

        // La otra salida de la regla B1. El permiso es `invoice.reject`, que ya existía en el
        // catálogo: inventar otro nombre habría dado 403 a todo el mundo para siempre.
        post("/{id}/reject-discrepancy") {
            call.requirePermission(permissionChecker, "invoice.reject")
            val invoiceId = call.invoiceId()

            val body = call.receive<RejectDiscrepancyRequest>()

            val detail = useCases.rejectDiscrepancy(
                invoiceId = invoiceId,
                reason = body.adjustmentReason,
                note = body.note,
                rejectedBy = call.requireUser(),
            )

            call.respond(HttpStatusCode.OK, detail)
        }
    }
}

/**
 * Los estados de una factura, para validar el filtro. La lista es la del ESQUEMA (el tipo
 * `invoice_status` de PostgreSQL): añadir un estado al enum del dominio sin añadirlo a la
 * migración rompería igual.
 */
private val INVOICE_STATUSES = listOf("PENDING", "IN_PROGRESS", "PENDING_TO_LOCATE", "COMPLETED", "CANCELLED")

private fun ApplicationCall.invoiceId(): Long = pathLong("id", "El identificador no es un número.")
