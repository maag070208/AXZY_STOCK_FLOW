package dev.axzy.stock.application.inventory

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.receiving.InvoiceLine
import java.time.Instant
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.util.UUID

/**
 * Los puertos de salida de existencias (M02, docs/flujos/existencias-y-ajustes.md).
 *
 * **Toda escritura pasa por el motor SQL** ([ADR-0015]): la API no toca
 * `stock_by_location` ni `product_stock` directamente. Por eso aquí no hay
 * `saveStock` ni nada parecido: hay [StockLedger.apply], que es
 * `record_movement`.
 */

/**
 * Una ubicación, tal como la identifica el contrato: **por su nombre visible**
 * (`04-B-C`, `RECEIVING`, `UNASSIGNED`).
 *
 * Se traduce a partes en la capa de aplicación y no en SQL, porque la regla de
 * composición vive en el dominio ([Location.displayName]) y tenerla dos veces
 * garantizaría que un día digan cosas distintas. Un test de ida y vuelta
 * (`LocationRefTest`) impide que se separen.
 */
sealed interface LocationRef {
    /** Un estante: sus tres partes. */
    data class Shelf(val hall: String, val code: String, val position: String) : LocationRef

    /** La zona de recepción: donde aterriza lo recibido y sin ubicar. */
    data object Receiving : LocationRef

    /** El saldo heredado del sistema anterior, del que se sabe que existe pero no dónde. */
    data object Unassigned : LocationRef

    /** El nombre visible canónico de esta referencia, para devolverlo en una respuesta. */
    val displayName: String
        get() = when (this) {
            is Shelf -> "$hall-$code-$position"
            Receiving -> "RECEIVING"
            Unassigned -> "UNASSIGNED"
        }

    companion object {
        const val RECEIVING_CODE = "RECEIVING"
        const val UNASSIGNED_CODE = "UNASSIGNED"

        /**
         * Reconstruye la referencia desde el nombre visible.
         *
         * `null` cuando no tiene forma de ubicación: el alfabeto de cada parte
         * excluye el guion, así que una cadena con otro número de partes no puede
         * corresponder a ninguna fila.
         */
        fun parse(raw: String): LocationRef? {
            val value = raw.trim().uppercase()
            if (value.isEmpty()) return null
            return when {
                value == UNASSIGNED_CODE -> Unassigned
                value == RECEIVING_CODE -> Receiving
                else -> {
                    val parts = value.split('-')
                    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
                    Shelf(parts[0], parts[1], parts[2])
                }
            }
        }
    }
}

/** De dónde salen los identificadores reales de producto y ubicación. */
interface InventoryCatalog {
    /** El producto **activo** con ese código: un producto inactivo no recibe movimientos. */
    fun activeProduct(code: ProductCode): Product?

    /** La ubicación **activa** de esa bodega, o `null` si no existe o está inactiva. */
    fun activeLocation(warehouseId: Long, ref: LocationRef): Location?

    /** La bodega por defecto: la que se usa cuando la petición no dice otra. */
    fun defaultWarehouseId(): Long
}

/**
 * Un movimiento, tal como lo necesita el motor SQL.
 *
 * Los tipos son los del dominio y no cadenas: un `MovementType` que no existe no
 * compila, y el adaptador es el único que traduce a los `enum` de PostgreSQL.
 */
data class MovementCommand(
    val productId: Long,
    val warehouseId: Long,
    val moveType: MovementType,
    val units: Quantity,
    val fromLocationId: Long? = null,
    val toLocationId: Long? = null,
    val container: String? = null,
    val orderNumber: String? = null,
    val adjustmentReason: AdjustmentReason? = null,
    val adjustmentOrigin: AdjustmentOrigin? = null,
    val userId: Long? = null,
    /** Clave de idempotencia: repetirla no duplica el movimiento (A8). */
    val clientUuid: UUID? = null,
    val note: String? = null,
)

/** El libro de inventario. Es la única puerta de escritura. */
interface StockLedger {
    /**
     * Saldo de una ubicación.
     *
     * Con `forUpdate` bloquea la fila hasta el fin de la transacción. Es lo que
     * permite decir «hay 100» **y** que siga siendo verdad al escribir: sin el
     * bloqueo, la comprobación y el descuento son dos verdades distintas
     * (requisito Q9).
     */
    fun balanceAt(productId: Long, locationId: Long, forUpdate: Boolean): Quantity

    /**
     * Aplica el movimiento por el motor y devuelve el identificador del kardex.
     *
     * Si el saldo no alcanza, la base lo rechaza; el caso de uso lo comprueba antes
     * para poder decir **cuánto hay**.
     */
    fun apply(command: MovementCommand): Long

    /** El movimiento de esa clave de idempotencia, si ya se aplicó. */
    fun movementIdOf(clientUuid: UUID): Long?

    /** Total del producto, leído de la proyección y no del historial (R-P3). */
    fun productTotal(productId: Long): Quantity
}

/** Una fila de existencias: producto, ubicación y cuánto hay. */
data class StockLine(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseId: Long,
    val warehouseCode: String,
    val quantity: Quantity,
)

/** Una fila del kardex, con los nombres visibles ya compuestos. */
data class KardexLine(
    val id: Long,
    val movedAt: java.time.Instant,
    val productCode: String,
    val productName: String,
    val moveType: String,
    val units: Quantity,
    val quantityAfter: Quantity,
    val fromLocationDisplayName: String?,
    val toLocationDisplayName: String?,
    val container: String?,
    val orderNumber: String?,
    val adjustmentReason: String?,
    val adjustmentOrigin: String?,
    val userId: Long?,
    val userName: String?,
    val note: String?,
)

/** Filtros del listado de existencias. `null` es «sin filtrar», nunca «falso». */
data class StockFilter(
    val productCode: ProductCode?,
    val location: LocationRef?,
    /** Por defecto `true`: un listado de miles de filas a cero no lo lee nadie. */
    val onlyWithStock: Boolean,
)

/** Filtros del kardex. */
data class KardexFilter(
    val productCode: ProductCode?,
    val moveType: MovementType?,
    val from: java.time.LocalDate?,
    val to: java.time.LocalDate?,
)

interface StockQueries {
    fun page(filter: StockFilter, request: PageRequest): Page<StockLine>
}

interface KardexQueries {
    fun page(filter: KardexFilter, request: PageRequest): Page<KardexLine>
}

/**
 * El enganche con la **línea de factura** de recepción (M03).
 *
 * Ubicar mercancía que viene de una factura no es solo mover saldo: hay que saber de
 * qué línea salió para que la factura pueda cerrarse, y hay que respetar la regla B1
 * —una línea con diferencia sin aprobar no se puede ubicar—. El puerto vive aquí, en
 * el módulo de inventario, porque es quien lo consume; lo implementa el adaptador de
 * facturas, que es quien tiene las líneas.
 */
interface LocatingLines {

    fun find(lineId: Long): InvoiceLine?

    /**
     * Anota lo ubicado en la línea: la cantidad acumulada, su estado y **la ubicación
     * de destino**, que en la tabla es una clave foránea.
     */
    fun markLocated(lineId: Long, line: InvoiceLine, locationId: Long, at: Instant): Unit
}
