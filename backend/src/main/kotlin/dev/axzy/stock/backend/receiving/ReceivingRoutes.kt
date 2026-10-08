package dev.axzy.stock.backend.receiving

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.receiving.NuevaLinea
import dev.axzy.stock.backend.receiving.ReceivingService
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.usuarioDelToken
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
data class NuevaLineaRequest(
    val productCode: String,
    val container: String? = null,
    val unitMeasure: String = "PZA",
    val quantity: Double,
    val boxQuantity: Double = 0.0,
    val unitsPerBox: Double? = null,
)

@Serializable
data class NuevaFacturaRequest(
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String = "NATIONAL",
    val lines: List<NuevaLineaRequest> = emptyList(),
)

@Serializable
data class CapturarLineaRequest(
    val physicalQuantity: Double,
    val physicalBoxQuantity: Double? = null,
)

@Serializable
data class AprobarFacturaRequest(
    val adjustmentReason: String,
    val note: String? = null,
)

@Serializable
data class FacturaCreadaResponse(val id: Long, val invoiceNumber: String)

fun Route.receivingRoutes(
    servicio: ReceivingService,
    permissionChecker: PermissionChecker,
) {

    suspend fun exigir(call: io.ktor.server.application.ApplicationCall, permiso: String) {
        when (val d = permissionChecker.comprobar(call, permiso)) {
            is PermissionDecision.Permitido -> Unit
            is PermissionDecision.Denegado -> throw ForbiddenException(d.code, d.message)
        }
    }

    suspend fun usuario(call: io.ktor.server.application.ApplicationCall): Long =
        call.usuarioDelToken() ?: throw UnauthorizedException()

    /** Traduce la cantidad del contrato (3 decimales) a la del dominio. */
    fun cantidad(v: Double): Quantity =
        Quantity.ofScaled(java.math.BigDecimal.valueOf(v).movePointRight(3).toLong())

    route("/v1/invoices") {

        // ── Crear ──────────────────────────────────────────────────────────
        post {
            exigir(call, "invoice.create")
            val cuerpo = call.receive<NuevaFacturaRequest>()

            if (cuerpo.invoiceNumber.isBlank()) {
                throw BadRequestException("FIELD_REQUIRED", "El número de factura es obligatorio.")
            }
            if (cuerpo.lines.isEmpty()) {
                // Una factura sin líneas es un documento que no sirve para nada y
                // bloquea el flujo: no se puede capturar ni ubicar nada.
                throw BadRequestException(
                    "FIELD_REQUIRED",
                    "Una factura necesita al menos una línea. Sin líneas no hay nada que recibir.",
                    mapOf("lines" to "vacío"),
                )
            }

            val fecha = runCatching { LocalDate.parse(cuerpo.receiptDate) }.getOrNull()
                ?: throw BadRequestException(
                    "INVALID_PARAMETER",
                    "La fecha de recepción debe tener el formato AAAA-MM-DD.",
                )

            val id = servicio.crearFactura(
                numero = cuerpo.invoiceNumber,
                fecha = fecha,
                tipo = cuerpo.invoiceType.uppercase(),
                lineas = cuerpo.lines.map {
                    NuevaLinea(
                        productCode = it.productCode,
                        container = it.container,
                        unitMeasure = it.unitMeasure,
                        quantity = cantidad(it.quantity),
                        boxQuantity = cantidad(it.boxQuantity),
                        unitsPerBox = it.unitsPerBox?.let { v -> cantidad(v) },
                    )
                },
                usuarioId = usuario(call),
            )

            call.respond(
                HttpStatusCode.Created,
                FacturaCreadaResponse(id, cuerpo.invoiceNumber),
            )
        }

        // ── Detalle ────────────────────────────────────────────────────────
        get("/{id}") {
            exigir(call, "invoice.view")
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")
            call.respond(HttpStatusCode.OK, servicio.factura(id))
        }

        // ── Bandeja de pendientes. Va ANTES de /{id} para que no la capture. ──
        get("/pending-approval") {
            // **Pantalla nueva: el legacy no la tenía.** Sin ella, una discrepancia
            // se quedaba bloqueada sin que nadie supiera que había que aprobarla.
            exigir(call, "invoice.view")
            call.respond(HttpStatusCode.OK, mapOf("items" to servicio.pendientesDeAprobacion()))
        }

        // ── Capturar el conteo físico ──────────────────────────────────────
        post("/{id}/lines/{lineId}/capture") {
            exigir(call, "invoice.capture")
            val facturaId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")
            val lineaId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador de línea no es un número.")

            val cuerpo = call.receive<CapturarLineaRequest>()

            val linea = servicio.capturarLinea(
                facturaId = facturaId,
                lineaId = lineaId,
                cantidadFisica = cantidad(cuerpo.physicalQuantity),
                cajasFisicas = cuerpo.physicalBoxQuantity?.let { cantidad(it) },
                usuarioId = usuario(call),
            )

            call.respond(HttpStatusCode.OK, linea)
        }

        // ── LA REGLA B1: aprobar la factura, no la línea ────────────────────
        post("/{id}/approve") {
            exigir(call, "invoice.approve")
            val facturaId = call.parameters["id"]?.toLongOrNull()
                ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")

            val cuerpo = call.receive<AprobarFacturaRequest>()

            val detalle = servicio.aprobarFactura(
                facturaId = facturaId,
                motivo = cuerpo.adjustmentReason,
                nota = cuerpo.note,
                aprobadorId = usuario(call),
            )

            call.respond(HttpStatusCode.OK, detalle)
        }
    }
}
