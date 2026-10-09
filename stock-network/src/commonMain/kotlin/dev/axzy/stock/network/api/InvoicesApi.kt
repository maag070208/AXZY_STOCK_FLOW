package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.invoice.ApproveInvoiceRequest
import dev.axzy.stock.network.dto.invoice.CaptureLineRequest
import dev.axzy.stock.network.dto.invoice.CreateInvoiceRequest
import dev.axzy.stock.network.dto.invoice.InvoiceDetailDto
import dev.axzy.stock.network.dto.invoice.InvoiceDto
import dev.axzy.stock.network.dto.invoice.RejectDiscrepancyRequest
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/invoices` — recepción
 *
 * Contrato: docs/historico/CONTRATO_API.md §5.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InvoicesApi internal constructor(private val http: StockHttpClient) {

    suspend fun list(query: ListQuery = ListQuery()): ApiOutcome<Page<InvoiceDto>> =
        http.getText("/invoices", query.toParams()).decode()

    suspend fun get(id: Long): ApiOutcome<InvoiceDetailDto> =
        http.getText("/invoices/$id").decode()

    suspend fun create(request: CreateInvoiceRequest): ApiOutcome<InvoiceDto> =
        http.postText("/invoices", stockJson.encodeToString(request)).decode()

    /**
     * Captura física de una línea.
     *
     * El `idempotencyKey` va en la cabecera **y** en el cuerpo: esta es la
     * llamada que más se reintenta sin señal, porque se hace en el andén con el
     * camión esperando.
     */
    suspend fun captureLine(
        invoiceId: Long,
        lineId: Long,
        request: CaptureLineRequest,
    ): ApiOutcome<InvoiceDetailDto> = http.postText(
        path = "/invoices/$invoiceId/lines/$lineId/capture",
        body = stockJson.encodeToString(request),
        idempotencyKey = request.idempotencyKey,
    ).decode()

    /**
     * Aprueba **la factura entera**, no una línea (decisión R-86): la acción de
     * aprobar opera sobre todas las líneas pendientes.
     *
     * `422 SEGREGATION_OF_DUTIES` si quien aprueba fue quien capturó. Es el único
     * error de permiso que **no** se arregla pidiendo permisos ni esperando.
     */
    suspend fun approveInvoice(invoiceId: Long, request: ApproveInvoiceRequest): ApiOutcome<InvoiceDetailDto> =
        http.postText(
            path = "/invoices/$invoiceId/approve",
            body = stockJson.encodeToString(request),
        ).decode()

    /**
     * Aprobación por línea.
     *
     * El contrato documenta las dos rutas y deja claro que la aprobación
     * efectiva es **por factura**; esta existe porque el endpoint está en el
     * contrato y algún cliente antiguo lo usa. Para el móvil, usa
     * [approveInvoice].
     */
    suspend fun approveLine(
        invoiceId: Long,
        lineId: Long,
        request: ApproveInvoiceRequest,
    ): ApiOutcome<InvoiceDetailDto> = http.postText(
        path = "/invoices/$invoiceId/lines/$lineId/approve",
        body = stockJson.encodeToString(request),
    ).decode()

    /** Rechaza la discrepancia y devuelve la línea a captura, con motivo obligatorio. */
    suspend fun rejectDiscrepancy(
        invoiceId: Long,
        request: RejectDiscrepancyRequest,
    ): ApiOutcome<InvoiceDetailDto> = http.postText(
        path = "/invoices/$invoiceId/reject-discrepancy",
        body = stockJson.encodeToString(request),
    ).decode()

    /**
     * Cierra la factura.
     *
     * `409 HAS_PENDING_LINES` con `details.pendingLines` si quedan líneas sin
     * resolver: el cliente puede leer el detalle y decir **cuántas** faltan, en
     * lugar de un «no se puede cerrar» sin cifra.
     */
    suspend fun close(invoiceId: Long): ApiOutcome<InvoiceDetailDto> =
        http.postText("/invoices/$invoiceId/close").decode()

    /**
     * Bandeja de pendientes de aprobación.
     *
     * **Pantalla nueva: el legacy no la tenía**, y sin ella una discrepancia se
     * quedaba bloqueada sin que nadie supiera que había que aprobarla.
     */
    suspend fun pendingApproval(query: ListQuery = ListQuery()): ApiOutcome<Page<InvoiceDto>> =
        http.getText("/invoices/pending-approval", query.toParams()).decode()
}
