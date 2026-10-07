package dev.axzy.stock.backend.catalog

import dev.axzy.stock.domain.catalog.ImportReport
import dev.axzy.stock.domain.catalog.RowRejection
import dev.axzy.stock.domain.value.Quantity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DTOs DEL CATÁLOGO — el contrato de transporte
 *
 * Estos objetos son **contratos de transporte, no el modelo de dominio**. El
 * legacy mezclaba ambos y de ahí venían los `Decimal` de Prisma contra los
 * `number` de JSON, los estados como cadenas sueltas y el `difference` que
 * siempre valía 0. Aquí la traducción está en un solo sitio y a la vista.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Una cantidad en el JSON, **como número con 3 decimales exactos**.
 *
 * El contrato es explícito: «número decimal con 3 decimales, nunca cadena»
 * (§1.7). Se emite con [JsonUnquotedLiteral], que escribe el valor tal cual, sin
 * comillas: `5193.000`. Si se emitiera como `Double` saldría `5193.0` (el valor
 * es correcto, pero el contrato documenta 3 decimales) y si se emitiera como
 * cadena rompería a cualquier cliente que sume sin convertir.
 *
 * Internamente guarda **milésimas enteras**, igual que `Quantity` del dominio:
 * una sola representación para el mismo concepto, sin errores de redondeo
 * binario.
 */
@Serializable(with = CantidadJsonSerializer::class)
@JvmInline
value class CantidadJson(val milesimas: Long) {

    /** Texto decimal con 3 decimales, que es lo que se escribe en el JSON. */
    fun comoTexto(): String {
        val negativo = milesimas < 0
        val absoluto = if (negativo) -milesimas else milesimas
        val entero = absoluto / 1000
        val fraccion = absoluto % 1000
        val signo = if (negativo) "-" else ""
        return "$signo$entero.${fraccion.toString().padStart(3, '0')}"
    }

    companion object {
        val CERO = CantidadJson(0)

        /** Desde el objeto de valor del dominio. */
        fun deCantidad(cantidad: Quantity): CantidadJson = CantidadJson(cantidad.value)

        /**
         * Desde un `NUMERIC(14,3)` de PostgreSQL.
         *
         * Se redondea a 3 decimales y no se trunca: truncar perdería la
         * milésima que la base ya redondeó al guardar.
         */
        fun deDecimal(valor: BigDecimal): CantidadJson =
            CantidadJson(valor.movePointRight(3).setScale(0, RoundingMode.HALF_UP).toLong())
    }
}

/**
 * Serializador de [CantidadJson].
 *
 * El descriptor declara STRING **solo como marcador**: la codificación real
 * emite un número JSON sin comillas. No se implementa la lectura porque estos
 * DTOs son de salida; si algún día hace falta leer cantidades, el sitio natural
 * es `Quantity.parse` del dominio, que sí valida.
 */
@OptIn(ExperimentalSerializationApi::class)
object CantidadJsonSerializer : KSerializer<CantidadJson> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CantidadJson", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: CantidadJson) {
        val texto = value.comoTexto()
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(texto))
        } else {
            // Fuera de JSON no hay número sin comillas posible: se escribe el
            // texto en lugar de fallar, para no atar el serializador a un
            // formato concreto.
            encoder.encodeString(texto)
        }
    }

    override fun deserialize(decoder: Decoder): CantidadJson =
        throw UnsupportedOperationException(
            "CantidadJson es un DTO de salida. Para leer una cantidad usa " +
                "Quantity.parse del dominio, que valida el formato y los decimales."
        )
}

/**
 * El sobre de paginación de §1.4, idéntico en todos los listados.
 *
 * `totalItems` es el total que cumple el filtro, no el tamaño de `items`.
 */
@Serializable
data class PageResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

/** Convierte una página del repositorio en el sobre del contrato. */
fun <T, R> Page<T>.aRespuesta(transformacion: (T) -> R): PageResponse<R> = PageResponse(
    items = items.map(transformacion),
    page = page,
    size = size,
    totalItems = totalItems,
    totalPages = totalPages,
)

// ── Productos ───────────────────────────────────────────────────────────────

/** Un producto del listado y del detalle (§3). */
@Serializable
data class ProductResponse(
    val id: Long,
    val productCode: String,
    val name: String,
    val description: String? = null,
    val unitMeasure: String,
    val externalId: Long? = null,
    val active: Boolean,
    /** Total derivado de `stock_by_location` + `stock_in_transit`. */
    val stock: CantidadJson,
)

fun ProductRecord.aDto() = ProductResponse(
    id = id,
    productCode = productCode,
    name = name,
    description = description,
    unitMeasure = unitMeasure,
    externalId = externalId,
    active = active,
    stock = CantidadJson.deDecimal(stock),
)

/** Alta de producto. */
@Serializable
data class ProductCreateRequest(
    val productCode: String,
    val name: String,
    val description: String? = null,
    val unitMeasure: String,
)

/**
 * Actualización de producto (reemplazo).
 *
 * `productCode` se acepta **solo para tolerar el ida y vuelta** de un cliente
 * que recibe el objeto del `GET` y lo devuelve modificado. Si trae un código
 * distinto se rechaza: es el código de barras impreso en las etiquetas y en los
 * estantes, y cambiarlo obliga a reetiquetar el almacén. Para eso existe una
 * acción propia en el catálogo de permisos (`product.sync_barcode`).
 */
@Serializable
data class ProductUpdateRequest(
    val productCode: String? = null,
    val name: String,
    val description: String? = null,
    val unitMeasure: String,
)

/**
 * Motivo del borrado lógico.
 *
 * Es obligatorio por contrato. La tabla `products` **no tiene columna para
 * guardarlo** (el esquema no se toca): se valida y se registra en el log de
 * aplicación con el id y el código, y la traza con autor y fecha corresponde a
 * `audit_log`, que es de la capa de auditoría.
 */
@Serializable
data class MotivoDeBorradoRequest(
    val reason: String? = null,
)

// ── Ubicaciones ─────────────────────────────────────────────────────────────

/** Una ubicación (§3). */
@Serializable
data class LocationResponse(
    val id: Long,
    val warehouseId: Long,
    val warehouseCode: String,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: String,
    val active: Boolean,
    /** Las tres partes sin repetir: `04-B-C`, `RECEPCION`. */
    val displayName: String,
)

fun LocationRecord.aDto() = LocationResponse(
    id = id,
    warehouseId = warehouseId,
    warehouseCode = warehouseCode,
    hall = hall,
    code = code,
    position = position,
    levels = levels,
    kind = kind,
    active = active,
    displayName = displayName,
)

/** Alta de ubicación. */
@Serializable
data class LocationCreateRequest(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int? = null,
    val kind: String? = null,
)

/**
 * Actualización de ubicación.
 *
 * `warehouseId` se acepta para tolerar el ida y vuelta, pero **no se puede
 * cambiar**: mover una ubicación de bodega movería el stock que contiene a otra
 * bodega sin ningún movimiento de por medio, que es exactamente la clase de
 * cambio invisible que descuadra un inventario.
 */
@Serializable
data class LocationUpdateRequest(
    val warehouseId: Long? = null,
    val hall: String? = null,
    val code: String? = null,
    val position: String? = null,
    val levels: Int? = null,
    val kind: String? = null,
    val active: Boolean? = null,
)

// ── Bodegas ─────────────────────────────────────────────────────────────────

/** Una bodega (§3). */
@Serializable
data class WarehouseResponse(
    val id: Long,
    val code: String,
    val name: String,
    val isDefault: Boolean,
    val active: Boolean,
)

/**
 * Listado de bodegas.
 *
 * **Sin `page`, `size`, `totalItems` ni `totalPages`**: §1.4 exceptúa las
 * bodegas porque son un catálogo cerrado que la interfaz necesita completo. El
 * sobre con paginación se omite a propósito en lugar de rellenarlo con valores
 * de una sola página: campos que siempre valen lo mismo son campos que el
 * cliente acabará interpretando mal.
 */
@Serializable
data class WarehouseListResponse(
    val items: List<WarehouseResponse>,
)

fun WarehouseRecord.aDto() = WarehouseResponse(
    id = id,
    code = code,
    name = name,
    isDefault = isDefault,
    active = active,
)

// ── Importación del catálogo ────────────────────────────────────────────────

/**
 * El informe de importación (§3).
 *
 * Se añaden dos campos que el contrato no lista, porque sin ellos el informe
 * puede leerse mal:
 *
 *  - `persisted`: cuántos productos se escribieron. En una simulación es 0, y
 *    sin este campo un informe con `accepted = 1398` parecería decir que se
 *    guardaron 1398 productos.
 *  - `stockPersisted`: **siempre `false` en esta fase**. El stock del archivo
 *    es el total del legacy y no dice en qué estantería está cada pieza
 *    (§3.5.1, hallazgo P5): asignarlo a ubicaciones sería inventar el dato. El
 *    import carga el CATÁLOGO y reporta el cuadre del archivo; la carga inicial
 *    de existencias es una decisión de negocio (A/B de §3.5.1) con su propio
 *    movimiento `INITIAL` en el kardex.
 */
@Serializable
data class ImportReportResponse(
    val dryRun: Boolean,
    val totalRows: Int,
    val accepted: Int,
    val rejected: Int,
    val totalStock: CantidadJson,
    val zeroStockCount: Int,
    val unitMeasures: List<String>,
    val rejections: List<RowRejectionResponse>,
    val persisted: Int,
    val stockPersisted: Boolean = false,
)

/**
 * Una fila rechazada con su número de fila.
 *
 * **El número de fila es obligatorio**: sin él, el usuario no puede corregir el
 * archivo. Se usa el número de fila FÍSICO del archivo (la cabecera es la 1),
 * no un índice interno, porque es el que se ve en Excel.
 */
@Serializable
data class RowRejectionResponse(
    val rowNumber: Int,
    /** Código de producto, cuando el rechazo se puede atribuir a uno. */
    val productCode: String? = null,
    val reason: String,
)

/**
 * Traduce el informe del dominio al DTO.
 *
 * @param persistidos cuántos productos se escribieron de verdad (0 en simulación).
 */
fun ImportReport.aDto(persistidos: Int) = ImportReportResponse(
    dryRun = isDryRun,
    totalRows = totalRows,
    accepted = acceptedCount,
    rejected = rejectedCount,
    totalStock = CantidadJson.deCantidad(totalStock),
    zeroStockCount = zeroStockCount,
    unitMeasures = unitMeasures,
    rejections = rejected.map { it.aDto() },
    // En una simulación no se escribió nada, y el informe lo dice en lugar de
    // dejar que el cliente lo deduzca del campo `dryRun`.
    persisted = if (isDryRun) 0 else persistidos,
    stockPersisted = false,
)

private fun RowRejection.aDto() = RowRejectionResponse(
    rowNumber = rowNumber,
    productCode = when (this) {
        is RowRejection.InvalidProductCode -> code
        is RowRejection.BlankName -> code
        is RowRejection.UnknownUnitMeasure -> code
        is RowRejection.InvalidStock -> code
        is RowRejection.DuplicateCode -> code
        is RowRejection.BlankRow -> null
    },
    reason = reason,
)
