package dev.axzy.stock.adapters.persistence.inventory

import dev.axzy.stock.adapters.persistence.shared.toQuantity
import dev.axzy.stock.application.inventory.MovementCommand
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.domain.value.Quantity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.util.UUID

/**
 * The inventory ledger on PostgreSQL: **the API does not write stock**.
 *
 * Every write goes through `record_movement`, the engine's function
 * ([ADR-0015]). That function applies the movement to the locations, recalculates the
 * product total and writes the ledger, in that order and within the transaction
 * of the use case. Writing `stock_by_location` from here would be the second
 * write path that the plan forbids, and `ArchitectureTest` would not detect it: what
 * prevents it is that this file has no `UPDATE` at all.
 */
class JdbcStockLedger(private val jdbc: JdbcClient) : StockLedger {

    override fun balanceAt(productId: Long, locationId: Long, forUpdate: Boolean): Quantity {
        // `FOR UPDATE` locks the row until the end of the transaction. That is what
        // makes the balance being checked still true when subtracting (Q9): without it,
        // two simultaneous outflows read 100 and both think they fit.
        val lock = if (forUpdate) " FOR UPDATE" else ""
        return jdbc.sql(
            "SELECT quantity FROM stock_by_location WHERE product_id = :product AND location_id = :location$lock",
        )
            .param("product", productId)
            .param("location", locationId)
            .query { rs, _ -> rs.getBigDecimal("quantity") }
            .optional()
            .orElse(null)
            .toQuantity()
    }

    override fun apply(command: MovementCommand): Long = try {
        jdbc.sql(
            """
            SELECT record_movement(
                :product, :warehouse, CAST(:moveType AS movement_type), :units,
                :fromLocation, :toLocation, :container, :orderNumber,
                CAST(:reason AS adjustment_reason), CAST(:origin AS adjustment_origin),
                :userId, :clientUuid, :note
            )
            """.trimIndent(),
        )
            .param("product", command.productId)
            .param("warehouse", command.warehouseId)
            .param("moveType", command.moveType.name)
            .param("units", BigDecimal.valueOf(command.units.value, Quantity.SCALE))
            .param("fromLocation", command.fromLocationId)
            .param("toLocation", command.toLocationId)
            .param("container", command.container)
            .param("orderNumber", command.orderNumber)
            .param("reason", command.adjustmentReason?.name)
            .param("origin", command.adjustmentOrigin?.name)
            .param("userId", command.userId)
            .param("clientUuid", command.clientUuid)
            .param("note", command.note)
            .query(Long::class.java)
            .single()
    } catch (error: DataIntegrityViolationException) {
        /*
         * Two requests with the SAME idempotency key at the same time (A8).
         *
         * Both passed the previous check —neither saw the movement of the
         * other— and the unique index on `client_uuid` decides: one inserts and the other
         * collides. Instead of returning an error, **the movement that won** is returned,
         * which is what the client asked for: the same result for the same key.
         *
         * The index name is checked so as not to swallow another violation (a
         * foreign key, for example) and answer "it already existed" to a real error.
         */
        val key = command.clientUuid
        if (key != null && error.message?.contains(CLIENT_UUID_INDEX) == true) {
            movementIdOf(key)?.let { return it }
        }
        throw error
    }

    override fun movementIdOf(clientUuid: UUID): Long? =
        jdbc.sql("SELECT id FROM kardex WHERE client_uuid = :uuid")
            .param("uuid", clientUuid)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    override fun productTotal(productId: Long): Quantity =
        jdbc.sql("SELECT quantity FROM product_stock WHERE product_id = :product")
            .param("product", productId)
            .query { rs, _ -> rs.getBigDecimal("quantity") }
            .optional()
            .orElse(null)
            .toQuantity()

    private companion object {
        /** The index that makes a mobile movement idempotent (V3). */
        const val CLIENT_UUID_INDEX = "kardex_client_uuid_idx"
    }
}
