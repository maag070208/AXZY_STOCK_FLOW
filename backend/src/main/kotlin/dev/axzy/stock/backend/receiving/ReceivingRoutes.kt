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

@Serializable
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

    suspend fun require(call: io.ktor.server.application.ApplicationCall, permission: String) {
        when (val d = permissionChecker.check(call, permission)) {
            is PermissionDecision.Permitted -> Unit
            is PermissionDecision.Denied -> throw ForbiddenException(d.code, d.message)
        }
    }

    suspend fun user(call: io.ktor.server.application.ApplicationCall): Long =
        call.userFromToken() ?: throw UnauthorizedException()

    /** Traduce la cantidad del contrato (3 decimales) a la del dominio. */
    fun quantity(v: Double): Quantity =
        Quantity.ofScaled(java.math.BigDecimal.valueOf(v).movePointRight(3).toLong())

    route("/v1/invoices") {

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
    }
}
