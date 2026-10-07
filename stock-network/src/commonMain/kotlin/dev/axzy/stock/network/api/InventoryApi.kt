package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.inventory.AdjustmentRequest
import dev.axzy.stock.network.dto.inventory.KardexEntryDto
import dev.axzy.stock.network.dto.inventory.KardexQuery
import dev.axzy.stock.network.dto.inventory.LocateRequest
import dev.axzy.stock.network.dto.inventory.MoveRequest
import dev.axzy.stock.network.dto.inventory.StockLevelDto
import dev.axzy.stock.network.dto.inventory.StockQuery
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/inventory` — existencias, kardex y movimientos
 *
 * Contrato: CONTRATO_API.md §4.
 *
 * **Los tres métodos que mueven inventario exigen `Idempotency-Key`**, y el
 * `idempotencyKey` del [AdjustmentRequest]/[LocateRequest]/[MoveRequest] viaja
 * también en la cabecera. No es redundancia: §1.5 la exige en la cabecera y el
 * contrato la declara además en el cuerpo. Un reintento sin cabecera duplica el
 * movimiento, y un movimiento duplicado es un descuadre de inventario que se
 * descubre con un conteo físico.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InventoryApi internal constructor(private val http: StockHttpClient) {

    /**
     * Existencias por producto y ubicación.
     *
     * Es la consulta que reemplaza a `GET /get-location-content` del legacy: lee
     * el saldo real en lugar de recalcularlo desde movimientos mutados.
     */
    suspend fun stock(
        query: StockQuery = StockQuery(),
        page: ListQuery = ListQuery(),
    ): ApiOutcome<Page<StockLevelDto>> = http.getText(
        path = "/inventory/stock",
        query = listOf(
            "productId" to query.productId?.toString(),
            "locationId" to query.locationId?.toString(),
            "warehouseId" to query.warehouseId?.toString(),
            "onlyWithStock" to query.onlyWithStock?.toString(),
        ) + page.toParams(),
    ).decode()

    /**
     * Kardex.
     *
     * **Es append-only y no se puede modificar por API**: en este cliente no hay
     * ningún método que haga `PUT` ni `DELETE` sobre un movimiento. Las
     * correcciones son movimientos nuevos.
     */
    suspend fun kardex(
        query: KardexQuery = KardexQuery(),
        page: ListQuery = ListQuery(),
    ): ApiOutcome<Page<KardexEntryDto>> = http.getText(
        path = "/inventory/kardex",
        query = listOf(
            "productCode" to query.productCode,
            "from" to query.from,
            "to" to query.to,
            "moveType" to query.moveType?.name,
        ) + page.toParams(),
    ).decode()

    /**
     * **La regla única de ajuste.** Los cuatro orígenes del legacy se unifican
     * aquí: merma por daño, faltante de surtido, conteo manual y discrepancia de
     * factura.
     *
     * Un ajuste por encima del umbral queda en `PENDING_APPROVAL` y la respuesta
     * lo indica; el cliente **no decide** si hace falta aprobación.
     */
    suspend fun adjustment(request: AdjustmentRequest): ApiOutcome<KardexEntryDto> =
        http.postText(
            path = "/inventory/adjustments",
            body = stockJson.encodeToString(request),
            idempotencyKey = request.idempotencyKey,
        ).decode()

    /**
     * Ubica mercancía recibida: de la ubicación de recepción al estante.
     *
     * **El total NO cambia**: es un traslado. Si esta llamada restara del total
     * (lo que hacía el legacy), el inventario bajaría con cada ubicación.
     *
     * `409 DISCREPANCY_NOT_APPROVED` si la línea tiene una diferencia sin
     * aprobar (regla B1).
     */
    suspend fun locate(request: LocateRequest): ApiOutcome<KardexEntryDto> =
        http.postText(
            path = "/inventory/locate",
            body = stockJson.encodeToString(request),
            idempotencyKey = request.idempotencyKey,
        ).decode()

    /** Traslado entre estantes de la misma bodega. El total sigue sin cambiar. */
    suspend fun move(request: MoveRequest): ApiOutcome<KardexEntryDto> =
        http.postText(
            path = "/inventory/move",
            body = stockJson.encodeToString(request),
            idempotencyKey = request.idempotencyKey,
        ).decode()
}
