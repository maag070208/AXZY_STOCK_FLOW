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
 * El libro de inventario sobre PostgreSQL: **la API no escribe existencias**.
 *
 * Todas las escrituras pasan por `registrar_movimiento`, la función del motor
 * ([ADR-0015]). Esa función aplica el movimiento a las ubicaciones, recalcula el
 * total del producto y escribe el kardex, en ese orden y dentro de la transacción
 * del caso de uso. Escribir `stock_by_location` desde aquí sería la segunda ruta de
 * escritura que el plan prohíbe, y `ArchitectureTest` no la detectaría: lo que la
 * impide es que este archivo no tiene ningún `UPDATE`.
 */
class JdbcStockLedger(private val jdbc: JdbcClient) : StockLedger {

    override fun balanceAt(productId: Long, locationId: Long, forUpdate: Boolean): Quantity {
        // `FOR UPDATE` bloquea la fila hasta el fin de la transacción. Es lo que hace
        // que el saldo que se comprueba siga siendo verdad al restar (Q9): sin él,
        // dos salidas simultáneas leen 100 y las dos creen que caben.
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
            SELECT registrar_movimiento(
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
         * Dos peticiones con la MISMA clave de idempotencia a la vez (A8).
         *
         * Las dos pasaron la comprobación previa —ninguna veía el movimiento de la
         * otra— y el índice único de `client_uuid` decide: una inserta y la otra
         * choca. En vez de devolver un error, se devuelve **el movimiento que ganó**,
         * que es lo que el cliente pidió: el mismo resultado para la misma clave.
         *
         * Se comprueba el nombre del índice para no tragarse otra violación (una
         * clave foránea, por ejemplo) y contestar «ya estaba» a un error real.
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
        /** El índice que hace idempotente un movimiento del móvil (V3). */
        const val CLIENT_UUID_INDEX = "kardex_client_uuid_idx"
    }
}
