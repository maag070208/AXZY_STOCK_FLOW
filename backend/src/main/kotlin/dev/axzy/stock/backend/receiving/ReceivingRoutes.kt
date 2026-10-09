package dev.axzy.stock.backend.receiving

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.receiving.NewInvoiceLine
import dev.axzy.stock.backend.receiving.ReceivingService
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.userFromToken
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.domain.value.Quantity
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DE RECEPCIÓN
 *
 * El flujo completo de una factura, con **la regla B1** en el centro: una línea con
 * discrepancia **no se puede ubicar** hasta que un supervisor apruebe la factura.
 *
 * **La aprobación es por factura** (decisión R-86) y por eso solo hay
 * `POST /v1/invoices/{id}/approve`. La variante por línea se documentó y se
 * **eliminó** del contrato en su día: aprobar de una en una obliga al supervisor a
 * repetir el mismo juicio N veces e invita a aprobar lo propio sin darse cuenta.
 * ═══════════════════════════════════════════════════════════════════════════
 */

@Serializable
data class NewInvoiceLineRequest(
    val productCode: String,
    val container: String? = null,
    val unitMeasure: String = "PZA",
    val quantity: Double,
    val boxQuantity: Double = 0.0,
    val unitsPerBox: Double? = null,
)

@Serializable
data class NewInvoiceRequest(
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String = "NATIONAL",
    val lines: List<NewInvoiceLineRequest> = emptyList(),
)

@Serializable
data class CaptureLineRequest(
    val physicalQuantity: Double,
    val physicalBoxQuantity: Double? = null,
)

/**
 * Rechazar una discrepancia: la mercancía NO se acepta y la línea se cancela.
 *
 * Comparte forma con la aprobación —motivo obligatorio y nota opcional— porque es la
 * misma decisión tomada al revés. Un motivo que no se exige en una de las dos salidas
 * acaba sin exigirse en ninguna.
 */
@Serializable
data class RejectDiscrepancyRequest(
    val adjustmentReason: String,
    val note: String? = null,
)

data class ApproveInvoiceRequest(
    val adjustmentReason: String,
    val note: String? = null,
)

@Serializable
data class InvoiceCreatedResponse(val id: Long, val invoiceNumber: String)

fun Route.receivingRoutes(
    service: ReceivingService,
    permissionChecker: PermissionChecker,
) {

    /**
     * Exige un permiso traduciendo la denegación al estado del contrato.
     *
     * **`UNAUTHENTICATED` es `401` y el resto `403` (§1.2), y esto era un defecto
     * real en toda la recepción.** Lanzaba `403` para cualquier denegación, así
     * que pedir una ruta de facturas sin token devolvía un estado distinto del que
     * devolvían el catálogo y los traslados. **La misma falta de token daba
     * respuestas distintas según la ruta.**
     *
     * Al cliente le importa: ante un `401` manda al usuario a iniciar sesión, y
     * ante un `403` a pedir un permiso. Con `403` para todo, manda a iniciar
     * sesión a quien ya la tiene abierta.
     *
     * Lo encontró el e2e de la API, que ahora recorre las rutas de todos los
     * módulos exigiendo el MISMO estado. Los tests de ruta no podían verlo: usan
     * un verificador que permite todo y nunca ejercitan una denegación real.
     */
    suspend fun require(call: io.ktor.server.application.ApplicationCall, permission: String) {
        when (val decision = permissionChecker.check(call, permission)) {
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
     * Los estados de una factura, para validar el filtro contra el enum real.
     *
     * Escritos a mano y no derivados del enum porque el enum del dominio
     * (`InvoiceStatus`) vive en `stock-domain` y esta lista tiene que coincidir con
     * el tipo `invoice_status` de PostgreSQL. Añadir un estado al enum sin añadirlo
     * a la migración rompería igual, así que la lista es la del ESQUEMA.
     */
    val INVOICE_STATUSES = listOf("PENDING", "IN_PROGRESS", "PENDING_TO_LOCATE", "COMPLETED", "CANCELLED")

    suspend fun user(call: io.ktor.server.application.ApplicationCall): Long =
        call.userFromToken() ?: throw UnauthorizedException()

    /** Traduce la cantidad del contrato (3 decimales) a la del dominio. */
    fun quantity(v: Double): Quantity =
        Quantity.ofScaled(java.math.BigDecimal.valueOf(v).movePointRight(3).toLong())

    route("/v1/invoices") {

        // ── Listado ────────────────────────────────────────────────────────
        get {
            require(call, "invoice.view")

            val brutoPagina = call.request.queryParameters["page"]
            val brutoTamano = call.request.queryParameters["size"]
            val pagina = brutoPagina?.toIntOrNull() ?: 0
            val tamano = brutoTamano?.toIntOrNull() ?: 50

            if (pagina < 0) {
                throw BadRequestException(
                    "INVALID_PAGINATION",
                    "El parámetro 'page' empieza en 0. Se recibió '$brutoPagina'.",
                )
            }
            // Se RECHAZA en lugar de recortar: recortar hace creer al cliente que
            // tiene todos los datos (§1.4).
            if (tamano < 1 || tamano > 200) {
                throw BadRequestException(
                    "INVALID_PAGINATION",
                    "El parámetro 'size' admite de 1 a 200. Se recibió '$brutoTamano'.",
                )
            }

            val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in INVOICE_STATUSES) {
                throw BadRequestException(
                    "INVALID_PARAMETER",
                    "El parámetro 'status' no admite el valor '$status'.",
                    mapOf("status" to status, "allowed" to INVOICE_STATUSES.joinToString(", ")),
                )
            }
            val search = call.request.queryParameters["q"]

            val items = service.list(status, search, pagina * tamano, tamano)
            val total = service.count(status, search)

            call.respond(
                HttpStatusCode.OK,
                InvoicePageResponse(
                    items = items,
                    page = pagina,
                    size = tamano,
                    totalItems = total,
                    totalPages = if (tamano == 0) 0 else ((total + tamano - 1) / tamano).toInt(),
                ),
            )
        }

        // ── Cerrar ─────────────────────────────────────────────────────────
        post("/{id}/close") {
            require(call, "invoice.close")
            val invoiceId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")

            // **La regla la aplica el servicio con el criterio del DOMINIO**
            // (`InvoiceLineStatus.blocksInvoiceClosing`): la ruta no repite los
            // estados. Duplicarlos aquí es cómo se acaba cerrando una factura con
            // una discrepancia viva.
            call.respond(HttpStatusCode.OK, service.close(invoiceId, user(call)))
        }

        // ── Crear ──────────────────────────────────────────────────────────
        post {
            require(call, "invoice.create")
            val body = call.receive<NewInvoiceRequest>()

            if (body.invoiceNumber.isBlank()) {
                throw BadRequestException("FIELD_REQUIRED", "El número de factura es obligatorio.")
            }
            if (body.lines.isEmpty()) {
                // Una factura sin líneas es un documento que no sirve para nada y
                // bloquea el flujo: no se puede capturar ni ubicar nada.
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

            val id = service.createInvoice(
                number = body.invoiceNumber,
                date = date,
                type = body.invoiceType.uppercase(),
                lines = body.lines.map {
                    NewInvoiceLine(
                        productCode = it.productCode,
                        container = it.container,
                        unitMeasure = it.unitMeasure,
                        quantity = quantity(it.quantity),
                        boxQuantity = quantity(it.boxQuantity),
                        unitsPerBox = it.unitsPerBox?.let { v -> quantity(v) },
                    )
                },
                userId = user(call),
            )

            call.respond(
                HttpStatusCode.Created,
                InvoiceCreatedResponse(id, body.invoiceNumber),
            )
        }

        // ── Detalle ────────────────────────────────────────────────────────
        get("/{id}") {
            require(call, "invoice.view")
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")
            call.respond(HttpStatusCode.OK, service.invoice(id))
        }

        // ── Bandeja de pendientes. Va ANTES de /{id} para que no la capture. ──
        get("/pending-approval") {
            // **Pantalla nueva: el legacy no la tenía.** Sin ella, una discrepancia
            // se quedaba bloqueada sin que nadie supiera que había que aprobarla.
            require(call, "invoice.view")
            call.respond(HttpStatusCode.OK, mapOf("items" to service.pendingApprovals()))
        }

        // ── Capturar el conteo físico ──────────────────────────────────────
        post("/{id}/lines/{lineId}/capture") {
            require(call, "invoice.capture")
            val invoiceId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")
            val lineId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador de línea no es un número.")

            val body = call.receive<CaptureLineRequest>()

            val line = service.captureLine(
                invoiceId = invoiceId,
                lineId = lineId,
                quantityPhysical = quantity(body.physicalQuantity),
                boxesPhysical = body.physicalBoxQuantity?.let { quantity(it) },
                userId = user(call),
            )

            call.respond(HttpStatusCode.OK, line)
        }

        // ── LA REGLA B1: aprobar la factura, no la línea ────────────────────
        post("/{id}/approve") {
            require(call, "invoice.approve")
            val invoiceId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")

            val body = call.receive<ApproveInvoiceRequest>()

            val detail = service.approveInvoice(
                invoiceId = invoiceId,
                reason = body.adjustmentReason,
                note = body.note,
                approverId = user(call),
            )

            call.respond(HttpStatusCode.OK, detail)
        }

        // ── LA OTRA SALIDA DE LA REGLA B1: rechazar ────────────────────────
        //
        // **Sin esta ruta, la única salida ante una discrepancia era aceptarla**, y eso
        // convierte el control en un trámite: un supervisor que no puede rechazar acaba
        // aprobando para desbloquear la factura. **El rechazo es lo que da sentido a la
        // aprobación.**
        post("/{id}/reject-discrepancy") {
            // El permiso es `invoice.reject` y **ya existía** en el catálogo de 76. Se
            // comprobó antes de inventar un nombre: escribir `invoice.reject_discrepancy`
            // habría dado 403 a todo el mundo para siempre, y es un fallo silencioso que
            // solo se nota al usarlo con el rol correcto.
            require(call, "invoice.reject")
            val invoiceId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")

            val body = call.receive<RejectDiscrepancyRequest>()

            val detail = service.rejectDiscrepancy(
                invoiceId = invoiceId,
                reason = body.adjustmentReason,
                note = body.note,
                rejectedBy = user(call),
            )

            call.respond(HttpStatusCode.OK, detail)
        }
    }
}
