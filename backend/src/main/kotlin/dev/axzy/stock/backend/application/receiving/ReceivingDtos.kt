package dev.axzy.stock.backend.application.receiving

import kotlinx.serialization.Serializable

/*
 * La forma de las peticiones y respuestas de recepción (contrato §5).
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

data class ApproveInvoiceRequest(
    val adjustmentReason: String,
    val note: String? = null,
)

/**
 * Rechazar una discrepancia: la mercancía NO se acepta y la línea se cancela.
 *
 * Comparte forma con la aprobación —motivo obligatorio y nota opcional— porque es la misma
 * decisión tomada al revés. Un motivo que no se exige en una de las dos salidas acaba sin
 * exigirse en ninguna.
 */
@Serializable
data class RejectDiscrepancyRequest(
    val adjustmentReason: String,
    val note: String? = null,
)

@Serializable
data class InvoiceCreatedResponse(val id: Long, val invoiceNumber: String)

/**
 * Una factura del listado: cabecera y contadores, **sin líneas**. Traer las líneas de cada
 * factura de la página sería un N+1 disfrazado; quien las necesita pide el detalle.
 */
@Serializable
data class InvoiceSummary(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String,
    val status: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val totalLines: Int,
    /** Líneas que IMPIDEN cerrar: sin capturar, con diferencia sin aprobar o asignadas. */
    val pendingApprovalLines: Int,
    val createdAt: String,
)

@Serializable
data class InvoicePageResponse(
    val items: List<InvoiceSummary>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

@Serializable
data class InvoiceDetail(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String,
    val status: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val totalLines: Int = 0,
    val pendingApprovalLines: Int = 0,
    val lines: List<InvoiceLineDto> = emptyList(),
)

@Serializable
data class InvoiceLineDto(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val container: String? = null,
    val quantity: String,
    val boxQuantity: String,
    val unitsPerBox: String? = null,
    val physicalQuantity: String? = null,
    val physicalBoxQuantity: String? = null,
    val locatedQuantity: String,
    /** La calcula LA BASE, con su columna generada. */
    val difference: String? = null,
    val status: String,
    val capturedBy: Long? = null,
    val capturedByName: String? = null,
    val capturedAt: String? = null,
    val approvedBy: Long? = null,
    val approvedByName: String? = null,
    val approvedAt: String? = null,
    val adjustmentReason: String? = null,
    val locationDisplayName: String? = null,
)

@Serializable
data class PendingApprovalDto(
    val invoiceId: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val status: String,
    val pendingLines: Int,
    val totalDifference: String,
)
