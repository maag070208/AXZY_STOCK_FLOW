package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.transfer.CancelTransferRequest
import dev.axzy.stock.network.dto.transfer.CreateTransferRequest
import dev.axzy.stock.network.dto.transfer.InTransitTransferDto
import dev.axzy.stock.network.dto.transfer.TransferDto
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/transfers` — traslados entre bodegas
 *
 * Contrato: CONTRATO_API.md §7.
 *
 * Ninguna de estas llamadas lleva `Idempotency-Key` porque el contrato **no las
 * lista** entre las que lo exigen, y aquí no se añade por iniciativa propia: la
 * idempotencia de un traslado la da su máquina de estados (`IN_TRANSIT` solo se
 * recibe una vez).
 * ═══════════════════════════════════════════════════════════════════════════
 */
class TransfersApi internal constructor(private val http: StockHttpClient) {

    suspend fun list(query: ListQuery = ListQuery()): ApiOutcome<Page<TransferDto>> =
        http.getText("/transfers", query.toParams()).decode()

    suspend fun get(id: Long): ApiOutcome<TransferDto> = http.getText("/transfers/$id").decode()

    suspend fun create(request: CreateTransferRequest): ApiOutcome<TransferDto> =
        http.postText("/transfers", stockJson.encodeToString(request)).decode()

    /**
     * Despacha: la mercancía **entra en tránsito y sigue contando** en el
     * inventario. No se modela como dos movimientos instantáneos porque el total
     * bajaría durante el viaje y los reportes mentirían.
     */
    suspend fun dispatch(id: Long): ApiOutcome<TransferDto> =
        http.postText("/transfers/$id/dispatch").decode()

    /** Recepción en la bodega destino: sale del tránsito y entra al estante. */
    suspend fun receive(id: Long): ApiOutcome<TransferDto> =
        http.postText("/transfers/$id/receive").decode()

    suspend fun cancel(id: Long, request: CancelTransferRequest = CancelTransferRequest()): ApiOutcome<TransferDto> =
        http.postText("/transfers/$id/cancel", stockJson.encodeToString(request)).decode()

    /**
     * Traslados despachados y no recibidos, con su responsable y los días
     * transcurridos.
     *
     * **Un traslado nunca recibido es una deuda visible, no un dato que se
     * pierde**: esta lista es la que hace que se pueda reclamar.
     */
    suspend fun inTransit(query: ListQuery = ListQuery()): ApiOutcome<Page<InTransitTransferDto>> =
        http.getText("/transfers/in-transit", query.toParams()).decode()
}
