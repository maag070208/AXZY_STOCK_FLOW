package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.order.CreateOrderRequest
import dev.axzy.stock.network.dto.order.OrderDto
import dev.axzy.stock.network.dto.order.OrderExportResultDto
import dev.axzy.stock.network.dto.order.PackageLineRequest
import dev.axzy.stock.network.dto.order.ShortageRequest
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/orders` — surtido
 *
 * Contrato: CONTRATO_API.md §6.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OrdersApi internal constructor(private val http: StockHttpClient) {

    suspend fun list(query: ListQuery = ListQuery()): ApiOutcome<Page<OrderDto>> =
        http.getText("/orders", query.toParams()).decode()

    /**
     * Detalle de una orden.
     *
     * AMBIGUO: el contrato **no documenta `GET /v1/orders/{id}`** ni la forma de
     * sus líneas; el móvil necesita las líneas para poder empaquetarlas. Se
     * devuelve el mismo [OrderDto] que el listado y se deja constancia del hueco
     * en el resumen: inventar un DTO de líneas que el backend no ha acordado
     * produciría un fallo de integración silencioso.
     */
    suspend fun get(id: Long): ApiOutcome<OrderDto> = http.getText("/orders/$id").decode()

    suspend fun create(request: CreateOrderRequest): ApiOutcome<OrderDto> =
        http.postText("/orders", stockJson.encodeToString(request)).decode()

    /**
     * Empaqueta una línea: descuenta stock y escribe `FULFILLMENT_OUT` en el
     * kardex.
     *
     * Si no alcanza, `422 INSUFFICIENT_STOCK` con `details.available`; el
     * faltante **no es un flujo aparte**, es un ajuste con origen
     * `FULFILLMENT_SHORTAGE`.
     */
    suspend fun packageLine(
        orderId: Long,
        lineId: Long,
        request: PackageLineRequest,
    ): ApiOutcome<OrderDto> = http.postText(
        path = "/orders/$orderId/lines/$lineId/package",
        body = stockJson.encodeToString(request),
        idempotencyKey = request.idempotencyKey,
    ).decode()

    /**
     * Confirma la orden: descuenta el stock pendiente y **dispara la exportación
     * al ERP**.
     *
     * No lleva `Idempotency-Key` porque es idempotente por estado: `409
     * ORDER_ALREADY_CONFIRMED` si ya estaba confirmada. Mandar una clave aquí
     * daría a entender que repetir crea otra confirmación, y no es así.
     */
    suspend fun confirm(orderId: Long): ApiOutcome<OrderDto> =
        http.postText("/orders/$orderId/confirm").decode()

    /**
     * Reintento manual de la exportación al ERP.
     *
     * Es idempotente **por `orderNumber` convertido**, no por clave de cliente:
     * por eso tampoco lleva cabecera de idempotencia.
     */
    suspend fun export(orderId: Long): ApiOutcome<OrderExportResultDto> =
        http.postText("/orders/$orderId/export").decode()

    /**
     * Registra el faltante de una línea.
     *
     * Delega en la regla única de ajuste; no es un segundo camino para mover
     * inventario.
     */
    suspend fun shortage(orderId: Long, request: ShortageRequest): ApiOutcome<OrderDto> =
        http.postText(
            path = "/orders/$orderId/shortage",
            body = stockJson.encodeToString(request),
        ).decode()
}
