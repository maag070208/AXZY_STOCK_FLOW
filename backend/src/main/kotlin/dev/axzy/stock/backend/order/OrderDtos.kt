package dev.axzy.stock.backend.order

import kotlinx.serialization.Serializable

/**
 * DTOs de surtido (contrato §6).
 *
 * **Las cantidades van como CADENA decimal de 3 decimales** (`"10.000"`), como
 * todo el contrato (§1.7).
 */
@Serializable
data class OrderLineResponse(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unitMeasure: String,
    val quantity: String,
    /**
     * **Lo ya descontado del inventario**, que es lo que significa este campo.
     * No es «lo empacado»: es el acumulado que el inventario ya refleja.
     */
    val suppliedQuantity: String,
    val supplied: Boolean,
    val status: String,
    val assignedUserId: Long? = null,
    val assignedUserName: String? = null,
    val locationId: Long? = null,
    val locationDisplayName: String? = null,
)

/** La cabecera. El listado devuelve solo esto. */
@Serializable
data class OrderResponse(
    val id: Long,
    val orderNumber: String,
    val packagingDate: String? = null,
    val status: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val converted: Boolean,
    val convertedAt: String? = null,
    val exportStatus: String? = null,
    val exportAttempts: Int,
    val exportLastError: String? = null,
    val totalLines: Int,
    val packagedLines: Int,
)

/**
 * El detalle: la cabecera **con sus líneas**.
 *
 * El contrato lo justifica: «la pantalla de empaque no puede funcionar sin ellas
 * y obligar a una segunda petición por línea sería absurdo con órdenes de decenas
 * de líneas».
 */
@Serializable
data class OrderDetailResponse(
    val id: Long,
    val orderNumber: String,
    val packagingDate: String? = null,
    val status: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val converted: Boolean,
    val convertedAt: String? = null,
    val exportStatus: String? = null,
    val exportAttempts: Int,
    val exportLastError: String? = null,
    val totalLines: Int,
    val packagedLines: Int,
    val lines: List<OrderLineResponse>,
)

@Serializable
data class OrderPageResponse(
    val items: List<OrderResponse>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

/** El resultado de un intento de exportación (§6). */
@Serializable
data class OrderExportResponse(
    val attempts: Int,
    val status: String,
    val lastError: String? = null,
)

@Serializable
data class PackageLineRequest(
    val quantity: String,
    val locationId: Long? = null,
)

/** Faltante durante el surtido: delega en la regla ÚNICA de ajuste. */
@Serializable
data class OrderShortageRequest(
    val lineId: Long,
    val quantity: String,
    val reason: String = "SHORTAGE",
    val note: String? = null,
)

@Serializable
data class OrderLineRequest(
    val productCode: String,
    val quantity: String,
)

@Serializable
data class OrderCreateRequest(
    val orderNumber: String? = null,
    val packagingDate: String? = null,
    val customerCode: String? = null,
    val concept: String? = null,
    val lines: List<OrderLineRequest>,
)
