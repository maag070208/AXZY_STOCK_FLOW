package dev.axzy.stock.backend.label

import kotlinx.serialization.Serializable

/**
 * DTOs de etiquetas (contrato §8).
 *
 * **`zpl` no viaja en el listado**, solo en el detalle: es un texto grande, la
 * lista de plantillas no lo necesita y mandarlo multiplicaría el tamaño de la
 * respuesta por el número de plantillas. Es una decisión del contrato y se
 * respeta.
 */

/** Un campo del `fieldSchema`, tal como viaja en el JSON. */
@Serializable
data class LabelFieldDto(
    val type: String = "string",
    val label: String,
    val required: Boolean = false,
)

@Serializable
data class LabelTemplateResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val size: String? = null,
    val isActive: Boolean,
    /** `campo → {type,label,required}`. */
    val fieldSchema: Map<String, LabelFieldDto> = emptyMap(),
)

/** El detalle: **el único que lleva el ZPL**. */
@Serializable
data class LabelTemplateDetailResponse(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val size: String? = null,
    val isActive: Boolean,
    val fieldSchema: Map<String, LabelFieldDto> = emptyMap(),
    val zpl: String,
)

@Serializable
data class LabelTemplateCreateRequest(
    val code: String,
    val name: String,
    val description: String? = null,
    val zpl: String,
    val size: String? = null,
    val fieldSchema: Map<String, LabelFieldDto> = emptyMap(),
    val isActive: Boolean = true,
)

@Serializable
data class LabelTemplateUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val zpl: String? = null,
    val size: String? = null,
    val fieldSchema: Map<String, LabelFieldDto>? = null,
    val isActive: Boolean? = null,
)

/** El lienzo de la previsualización, en milímetros. */
@Serializable
data class LabelPreviewCanvas(
    val widthMm: Double,
    val heightMm: Double,
    val copies: Int,
)

@Serializable
data class LabelPreviewResponse(
    val zpl: String,
    val preview: LabelPreviewCanvas,
)

@Serializable
data class LabelPreviewRequest(
    val templateId: Long,
    val data: Map<String, String> = emptyMap(),
)

@Serializable
data class LabelPrintRequest(
    val templateId: Long,
    /** Copias por cada juego de datos. Por defecto 1. */
    val copies: Int = 1,
    /** Un juego de datos por etiqueta. */
    val data: List<Map<String, String>>,
)

@Serializable
data class LabelPrintResponse(
    val jobIds: List<Long>,
    val status: String,
)

@Serializable
data class PrintJobResponse(
    val id: Long,
    val templateCode: String,
    val copies: Int,
    val status: String,
    val attempts: Int,
    val lastError: String? = null,
    val printedAt: String? = null,
    val createdAt: String,
)

@Serializable
data class PrintJobPageResponse(
    val items: List<PrintJobResponse>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

/** El cuerpo con el que la impresora reporta el resultado (§8). */
@Serializable
data class PrintJobUpdateRequest(
    val status: String,
    val error: String? = null,
)

/** Y su respuesta, con el `jobId` que confirma qué trabajo se actualizó. */
@Serializable
data class PrintJobUpdateResponse(
    val jobId: Long,
    val status: String,
    val error: String? = null,
)

@Serializable
data class ReprintRequest(
    val reason: String,
)
