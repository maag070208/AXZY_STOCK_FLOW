package dev.axzy.stock.network.dto.label

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ETIQUETAS E IMPRESIÓN — DTOs de `/v1/label-templates`, `/v1/labels`, `/v1/prints`
 *
 * Contrato: docs/historico/CONTRATO_API.md §8.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Una plantilla de etiqueta, **sin `zpl`**.
 *
 * El `zpl` no se devuelve en el listado a propósito: es grande y no se
 * necesita para pintar la lista. Solo aparece en el detalle
 * ([LabelTemplateDetailDto]).
 */
@Serializable
data class LabelTemplateDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    /** Tamaño físico, `4x6` en pulgadas. */
    val size: String? = null,
    val isActive: Boolean = true,
    /** Los campos que la plantilla espera, indexados por nombre de campo. */
    val fieldSchema: Map<String, LabelFieldSpecDto> = emptyMap(),
)

/**
 * La especificación de un campo de la plantilla.
 *
 * `type` se deja como texto porque el contrato solo muestra `string` y `number`
 * y no enumera el conjunto: inventar un enum aquí haría fallar el parseo el día
 * que aparezca un `date`.
 */
@Serializable
data class LabelFieldSpecDto(
    val type: String,
    val label: String,
    val required: Boolean = false,
)

/**
 * El detalle de una plantilla: **aquí sí viene el `zpl`**.
 */
@Serializable
data class LabelTemplateDetailDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val size: String? = null,
    val isActive: Boolean = true,
    val fieldSchema: Map<String, LabelFieldSpecDto> = emptyMap(),
    val zpl: String? = null,
)

/**
 * Cuerpo de `POST /v1/labels/preview`.
 *
 * `data` es un mapa de valores heterogéneos (el ejemplo trae
 * `"quantity": 100` y textos), así que se tipa como [JsonElement] en lugar de
 * `String`: convertir todo a texto haría que el ZPL de una cantidad imprimiera
 * `100.0` o `"100"` según el día.
 */
@Serializable
data class PreviewLabelRequest(
    val templateId: Long,
    val data: Map<String, JsonElement> = emptyMap(),
)

/** Respuesta de `POST /v1/labels/preview`: el ZPL resuelto, sin imprimir. */
@Serializable
data class LabelPreviewResponse(
    val zpl: String,
    val preview: LabelPreviewSizeDto,
)

/** Dimensiones de la etiqueta y número de copias, para poder dibujarla en pantalla. */
@Serializable
data class LabelPreviewSizeDto(
    val widthMm: Double,
    val heightMm: Double,
    val copies: Int = 1,
)

/**
 * Cuerpo de `POST /v1/labels/print`.
 *
 * `data` es una **lista**: cada elemento es una etiqueta. Imprimir N copias de
 * una lista de M datos produce hasta N×M etiquetas, y cada una con su job.
 */
@Serializable
data class PrintLabelsRequest(
    val templateId: Long,
    val copies: Int = 1,
    val data: List<Map<String, JsonElement>> = emptyList(),
)

/**
 * Respuesta `202 Accepted` del trabajo de impresión.
 *
 * **El trabajo queda `PENDING` hasta que la impresora confirma.** El legacy
 * marcaba la etiqueta como impresa sin esperar la respuesta, así que si la
 * Zebra estaba apagada la etiqueta se perdía para siempre.
 */
@Serializable
data class PrintLabelsResponse(
    val jobIds: List<Long> = emptyList(),
    val status: LabelJobStatus = LabelJobStatus.PENDING,
)

/** Estado de un trabajo de impresión. `PRINTED` solo lo pone la impresora. */
@Serializable
enum class LabelJobStatus {
    @SerialName("PENDING")
    PENDING,

    @SerialName("SENT")
    SENT,

    @SerialName("PRINTED")
    PRINTED,

    @SerialName("FAILED")
    FAILED,
}

/** Un trabajo de impresión. */
@Serializable
data class LabelJobDto(
    val id: Long,
    val templateCode: String,
    val copies: Int = 1,
    val status: LabelJobStatus = LabelJobStatus.PENDING,
    val attempts: Int = 0,
    val lastError: String? = null,
    val printedAt: String? = null,
    val createdAt: String? = null,
)

/**
 * Cuerpo de `POST /v1/labels/jobs/{id}/reprint`.
 *
 * Exige motivo y **genera un trabajo nuevo enlazado al original**: la
 * trazabilidad de una reimpresión exige saber quién la pidió y por qué. No se
 * reutiliza el registro.
 */
@Serializable
data class ReprintLabelRequest(
    val reason: String,
)

/**
 * Cuerpo de `PATCH /v1/prints` — **la llamada que hace la impresora**.
 *
 * No exige JWT de usuario sino credencial de servicio: el legacy tenía este
 * endpoint abierto sin ninguna credencial. Este DTO existe en el módulo del
 * móvil porque la app también puede actuar de puente con una Zebra local, y en
 * ese caso debe enviar la misma forma.
 */
@Serializable
data class PrintCallbackRequest(
    val jobId: Long,
    val status: LabelJobStatus,
    val error: String? = null,
)
