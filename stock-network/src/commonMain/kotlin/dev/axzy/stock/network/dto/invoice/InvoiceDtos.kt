package dev.axzy.stock.network.dto.invoice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RECEPCIÓN — DTOs de `/v1/invoices`
 *
 * Contrato: docs/historico/CONTRATO_API.md §5.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Una factura en el listado.
 *
 * `totalLines`, `locatedLines` y `pendingLines` son contadores derivados que el
 * servidor calcula: son lo que la pantalla necesita para decir «12 líneas, 8
 * ubicadas, 4 pendientes» sin cargar las líneas de cada factura.
 */
@Serializable
data class InvoiceDto(
    val id: Long,
    val invoiceNumber: String,
    /** Fecha operativa `YYYY-MM-DD`, sin hora: es la fecha de negocio, no la del reloj. */
    val receiptDate: String,
    /**
     * Tipo de factura (`NATIONAL` en el ejemplo).
     *
     * AMBIGUO: el contrato no enumera los valores posibles, así que se deja como
     * texto en lugar de inventar un enum que el backend podría no respetar.
     */
    val invoiceType: String? = null,
    val status: InvoiceStatus = InvoiceStatus.PENDING,
    val customerCode: String? = null,
    val concept: String? = null,
    val totalLines: Int = 0,
    val locatedLines: Int = 0,
    val pendingLines: Int = 0,
    /** Instante ISO 8601 en UTC, con `Z`. */
    val createdAt: String? = null,
)

/** Estados de una factura. Conjunto cerrado según el contrato. */
@Serializable
enum class InvoiceStatus {
    @SerialName("PENDING")
    PENDING,

    @SerialName("IN_PROGRESS")
    IN_PROGRESS,

    @SerialName("PENDING_TO_LOCATE")
    PENDING_TO_LOCATE,

    @SerialName("COMPLETED")
    COMPLETED,

    @SerialName("CANCELLED")
    CANCELLED,
}

/**
 * El detalle de una factura, **con sus líneas**.
 *
 * No se reutiliza [InvoiceDto] con una lista opcional porque el contrato define
 * dos formas distintas para el mismo recurso, y una lista opcional acabaría
 * siendo un campo que a veces está y a veces no: eso es exactamente lo que
 * rompe a los clientes.
 */
@Serializable
data class InvoiceDetailDto(
    val id: Long,
    val invoiceNumber: String,
    val status: InvoiceStatus = InvoiceStatus.PENDING,
    val lines: List<InvoiceLineDto> = emptyList(),
)

/**
 * Una línea de factura.
 *
 * **`difference` la calcula el servidor** y es `physicalQuantity - quantity`.
 * En el legacy estaba declarada en el modelo, el DTO, el mapper y la respuesta,
 * y **siempre valía 0**: se declaraba pero nunca se calculaba. Aquí viene del
 * servidor, que la deriva de una columna generada de la base.
 *
 * El dominio tiene su propia `difference` derivada
 * (`InvoiceLine.difference`), y el mapper comprueba que las dos coincidan: si el
 * servidor manda 0 con una discrepancia real, se ve en un test, no en producción
 * tres meses después.
 */
@Serializable
data class InvoiceLineDto(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val container: String? = null,
    val unitMeasure: String = "PZA",
    /** Cantidad según la factura del proveedor. */
    val quantity: Double,
    val boxQuantity: Double = 0.0,
    val unitsPerBox: Double = 0.0,
    /** Cantidad contada físicamente. `null` mientras no se ha contado. */
    val physicalQuantity: Double? = null,
    val physicalBoxQuantity: Double? = null,
    val locatedQuantity: Double = 0.0,
    /** `physicalQuantity - quantity`, calculada por el servidor. */
    val difference: Double? = null,
    val status: InvoiceLineStatus = InvoiceLineStatus.PENDING,
    val capturedBy: Long? = null,
    val capturedByName: String? = null,
    val capturedAt: String? = null,
    val approvedBy: Long? = null,
    val approvedByName: String? = null,
    val approvedAt: String? = null,
    val adjustmentReason: String? = null,
    val locationId: Long? = null,
    val locationDisplayName: String? = null,
)

/**
 * Estados de una línea.
 *
 * **Ojo con `PENDING_APPROVAL`**: el contrato lo lista como estado de línea y
 * el dominio no lo tiene como estado propio (allí la discrepancia sin aprobar
 * es `DISCREPANCY`, que es lo que impide ubicar). El mapper
 * `InvoiceLineDto.toDomain()` traduce `PENDING_APPROVAL` → `DISCREPANCY` y lo
 * documenta; es la única interpretación que mantiene la regla B1 (toda
 * discrepancia bloquea la ubicación).
 */
@Serializable
enum class InvoiceLineStatus {
    @SerialName("PENDING")
    PENDING,

    @SerialName("CAPTURED")
    CAPTURED,

    @SerialName("DISCREPANCY")
    DISCREPANCY,

    @SerialName("PENDING_APPROVAL")
    PENDING_APPROVAL,

    @SerialName("APPROVED")
    APPROVED,

    @SerialName("ASSIGNED")
    ASSIGNED,

    @SerialName("LOCATED")
    LOCATED,

    @SerialName("CANCELLED")
    CANCELLED,
}

/**
 * Cuerpo de `POST /v1/invoices`.
 *
 * AMBIGUO: el contrato documenta la ruta `GET|POST /v1/invoices` pero **no
 * publica el cuerpo del alta**. Se incluyen los campos que la propia respuesta
 * declara, con `lines` opcional; si el backend pide otra cosa, este DTO es el
 * único sitio a tocar.
 */
@Serializable
data class CreateInvoiceRequest(
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String? = null,
    val customerCode: String? = null,
    val concept: String? = null,
    val lines: List<CreateInvoiceLineRequest> = emptyList(),
)

/** AMBIGUO: línea del alta de factura, tampoco documentada en el contrato. */
@Serializable
data class CreateInvoiceLineRequest(
    val productId: Long,
    val quantity: Double,
    val boxQuantity: Double = 0.0,
    val unitsPerBox: Double = 0.0,
    val container: String? = null,
)

/**
 * Cuerpo de la captura física: `POST /v1/invoices/{id}/lines/{lineId}/capture`.
 *
 * El servidor calcula la diferencia y decide el estado: `CAPTURED` si coincide,
 * `DISCREPANCY` si no. El cliente **no decide el estado**: si lo decidiera,
 * habría dos implementaciones de la misma regla y una se quedaría atrás.
 */
@Serializable
data class CaptureLineRequest(
    val physicalQuantity: Double,
    val physicalBoxQuantity: Double,
    val idempotencyKey: String,
)

/**
 * Cuerpo de la aprobación, por factura o por línea.
 *
 * La aprobación **opera sobre todas las líneas pendientes de la factura**
 * (decisión R-86), y el motivo del ajuste es obligatorio: autorizar una
 * diferencia sin decir por qué es un ajuste sin rastro.
 */
@Serializable
data class ApproveInvoiceRequest(
    val adjustmentReason: String,
    val note: String? = null,
)

/**
 * Cuerpo de `POST /v1/invoices/{id}/reject-discrepancy`.
 *
 * AMBIGUO: el contrato documenta la ruta y no el cuerpo. Se exige `reason`
 * porque rechazar una discrepancia sin motivo deja la línea en un limbo sin
 * explicación; el dominio también lo exige (`ReasonRequired`).
 */
@Serializable
data class RejectDiscrepancyRequest(
    val reason: String,
    val note: String? = null,
)
