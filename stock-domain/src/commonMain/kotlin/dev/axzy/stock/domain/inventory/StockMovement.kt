package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA MATRIZ MOVIMIENTO → EFECTO EN STOCK
 *
 * Este archivo es el que corrige el defecto más grave del legacy. El problema
 * no era un `if` mal escrito: era que **el efecto de cada movimiento no estaba
 * declarado**. Vivía en un `if/else` (`updateStoreStock`) con una regla por
 * defecto —"cualquier otro tipo resta"— que hacía que `LOCATION` restara del
 * total sin que nadie lo hubiera decidido.
 *
 * Aquí cada movimiento **declara su efecto explícitamente**. Consecuencias:
 *
 *   1. Un `when` exhaustivo sobre [StockMovement] **no compila** si aparece un
 *      movimiento nuevo sin declarar su efecto. El compilador impide el olvido.
 *   2. **Ningún movimiento resta del total.** El total es una suma derivada de
 *      las ubicaciones. El bug de doble descuento al ubicar **deja de ser
 *      expresable**, que es mejor que "corregido".
 *   3. El tránsito entre bodegas es explícito ([StockEffect.EnterTransit] y
 *      [StockEffect.LeaveTransit]) en lugar de un efecto colateral.
 *
 * Referencia: PLAN_ALMACEN_INTEGRAL.md §2.7 (el bug verificado) y §3.2-ter.
 * Decisión R-65: ubicar NO baja el total.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Efecto de un movimiento sobre el inventario.
 *
 * El total (`product_stock`) es **siempre una suma derivada** de
 * `Σ stock_by_location + Σ stock_in_transit`. Ningún efecto lo modifica
 * directamente: esa es la garantía estructural contra el descuadre.
 */
sealed interface StockEffect {

    /**
     * El movimiento aumenta el inventario físico porque **entra mercancía nueva**.
     * Es el único efecto que incrementa el total, y solo ocurre por:
     * una carga inicial, una recepción capturada o un ajuste positivo autorizado.
     */
    data class AddToLocation(val location: LocationCode) : StockEffect

    /**
     * El movimiento saca mercancía del almacén de forma definitiva.
     * Es el único efecto que decrementa el total, y **siempre exige motivo**.
     */
    data class RemoveFromLocation(val location: LocationCode) : StockEffect

    /**
     * Mercancía que cambia de estante **dentro de la misma bodega**.
     * El total es invariante: sale de una ubicación y entra en otra.
     *
     * Este es el efecto que usa **ubicar** durante la recepción: sale de la
     * ubicación de recepción y entra al estante (decisión R-65).
     */
    data class TransferWithinWarehouse(
        val from: LocationCode,
        val to: LocationCode,
    ) : StockEffect

    /**
     * Mercancía que sale de una bodega hacia otra: entra en **tránsito**.
     * Sigue contando en el inventario total, pero ya no está en un estante.
     * El legacy tenía este concepto a medias y sin saberlo: `PENDING-TO-OUT`
     * "no afectaba al stock" por accidente.
     */
    data class EnterTransit(
        val from: LocationCode,
        val toWarehouseId: Long,
    ) : StockEffect

    /**
     * Mercancía que llega desde el tránsito: sale del tránsito y entra al
     * estante de la bodega destino. El total sigue siendo invariante.
     */
    data class LeaveTransit(
        val to: LocationCode,
        val fromWarehouseId: Long,
    ) : StockEffect

    /**
     * Movimiento **sin efecto en el inventario**.
     *
     * No es un efecto "vacío por descuido": es la declaración explícita de que
     * este movimiento es una **intención**, no un hecho. Ejemplo: reservar
     * mercancía para una orden antes de sacarla físicamente. El legacy lo tenía
     * como un caso especial escondido (`if moveType == 'PENDING-TO-OUT' return`).
     */
    data object NoInventoryChange : StockEffect
}

/**
 * Tipo de movimiento del ledger. **Enum cerrado**: la base de datos usa el
 * mismo conjunto, así que un valor inválido es imposible.
 *
 * Los nombres son los del legacy (`INITIAL`, `IN`, `OUT`, `LOCATION`…) más los
 * nuevos que el modelo necesita, para que la migración sea trazable.
 */
enum class MovementType {
    /** Carga inicial del catálogo. Entra mercancía por primera vez. */
    INITIAL,

    /** Entrada por recepción de factura: mercancía nueva al almacén. */
    RECEIPT_IN,

    /** Entrada por ajuste positivo autorizado (conteo, devolución de proveedor). */
    ADJUSTMENT_IN,

    /** Salida definitiva por surtido de una orden. */
    FULFILLMENT_OUT,

    /** Salida por merma: requiere motivo obligatorio. */
    ADJUSTMENT_OUT,

    /**
     * Ubicación de mercancía recibida: sale de la ubicación de recepción y
     * entra al estante. **NO cambia el total** (decisión R-65).
     * En el legacy esto restaba del total por un efecto por defecto: era el bug.
     */
    LOCATE,

    /** Traslado entre estantes de la misma bodega. No cambia el total. */
    MOVE_WITHIN_WAREHOUSE,

    /** Salida de una bodega hacia otra: entra en tránsito. */
    TRANSFER_OUT,

    /** Llegada desde el tránsito: entra al estante destino. */
    TRANSFER_IN,

    /** Intención de salida (reserva para una orden). No cambia el inventario. */
    PENDING_OUT,
}

/**
 * Un movimiento del ledger, con su efecto **declarado**.
 *
 * Modelado como `sealed interface` para que el `when` que lo consume sea
 * exhaustivo y el compilador detecte un movimiento nuevo sin tratar.
 */
sealed interface StockMovement {

    val type: MovementType
    val quantity: Quantity

    /** La ubicación de la que sale la mercancía, si aplica. */
    val from: LocationCode?

    /** La ubicación a la que entra la mercancía, si aplica. */
    val to: LocationCode?

    /**
     * **El efecto declarado.** Es la pieza central: el consumidor no deduce el
     * efecto, lo lee. Añadir un movimiento nuevo obliga a declararlo aquí y el
     * `when` de abajo deja de compilar hasta que se haga.
     */
    val effect: StockEffect
}

// ─────────────────────────────────────────────────────────────────────────────
// Implementaciones. Cada una declara su efecto en el constructor.
// ─────────────────────────────────────────────────────────────────────────────

/** Carga inicial del catálogo: entra mercancía por primera vez. */
data class InitialLoad(
    override val quantity: Quantity,
    val location: LocationCode,
    override val to: LocationCode = location,
) : StockMovement {
    override val type = MovementType.INITIAL
    override val from: LocationCode? = null
    override val effect: StockEffect = StockEffect.AddToLocation(location)
}

/** Recepción de factura capturada: entra mercancía nueva al almacén. */
data class ReceiptIn(
    override val quantity: Quantity,
    val receivingLocation: LocationCode,
) : StockMovement {
    override val type = MovementType.RECEIPT_IN
    override val from: LocationCode? = null
    override val to: LocationCode = receivingLocation
    override val effect: StockEffect = StockEffect.AddToLocation(receivingLocation)
}

/** Ajuste positivo autorizado: requiere motivo (invariante I6). */
data class AdjustmentIn(
    override val quantity: Quantity,
    val location: LocationCode,
    val reason: String,
) : StockMovement {
    init {
        require(reason.isNotBlank()) {
            "Todo ajuste de entrada exige un motivo. Un ajuste sin motivo es un agujero de auditoría."
        }
    }

    override val type = MovementType.ADJUSTMENT_IN
    override val from: LocationCode? = null
    override val to: LocationCode = location
    override val effect: StockEffect = StockEffect.AddToLocation(location)
}

/** Surtido de una orden: salida definitiva del almacén. */
data class FulfillmentOut(
    override val quantity: Quantity,
    val location: LocationCode,
    val orderNumber: String,
) : StockMovement {
    init {
        require(orderNumber.isNotBlank()) { "Una salida por surtido exige el número de orden" }
    }

    override val type = MovementType.FULFILLMENT_OUT
    override val from: LocationCode? = location
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.RemoveFromLocation(location)
}

/** Merma: salida con **motivo obligatorio** (decisión B2 / invariante I6). */
data class AdjustmentOut(
    override val quantity: Quantity,
    val location: LocationCode,
    val reason: String,
) : StockMovement {
    init {
        require(reason.isNotBlank()) {
            "Toda merma exige un motivo. Nunca se borra inventario: se registra el movimiento."
        }
    }

    override val type = MovementType.ADJUSTMENT_OUT
    override val from: LocationCode? = location
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.RemoveFromLocation(location)
}

/**
 * Ubicar mercancía recibida: **el movimiento del bug del legacy**.
 *
 * Sale de la ubicación de recepción y entra al estante. El total **no cambia**.
 * En el legacy esto restaba del total (dos veces, de hecho), y una caja de 100
 * dejaba el inventario 100 unidades por debajo.
 */
data class Locate(
    override val quantity: Quantity,
    val fromReceiving: LocationCode,
    val toShelf: LocationCode,
) : StockMovement {
    init {
        require(fromReceiving != toShelf) {
            "Ubicar requiere que la ubicación de destino sea distinta de la de recepción"
        }
    }

    override val type = MovementType.LOCATE
    override val from: LocationCode? = fromReceiving
    override val to: LocationCode = toShelf
    override val effect: StockEffect =
        StockEffect.TransferWithinWarehouse(from = fromReceiving, to = toShelf)
}

/** Traslado entre estantes de la misma bodega. Total invariante. */
data class MoveWithinWarehouse(
    override val quantity: Quantity,
    override val from: LocationCode,
    override val to: LocationCode,
) : StockMovement {
    init {
        require(from != to) { "Un traslado requiere origen y destino distintos" }
    }

    override val type = MovementType.MOVE_WITHIN_WAREHOUSE
    override val effect: StockEffect = StockEffect.TransferWithinWarehouse(from = from, to = to)
}

/** Salida de una bodega hacia otra: la mercancía entra en tránsito. */
data class TransferOut(
    override val quantity: Quantity,
    val fromLocation: LocationCode,
    val toWarehouseId: Long,
) : StockMovement {
    override val type = MovementType.TRANSFER_OUT
    override val from: LocationCode? = fromLocation
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.EnterTransit(fromLocation, toWarehouseId)
}

/** Llegada desde el tránsito: entra al estante de la bodega destino. */
data class TransferIn(
    override val quantity: Quantity,
    val toLocation: LocationCode,
    val fromWarehouseId: Long,
) : StockMovement {
    override val type = MovementType.TRANSFER_IN
    override val from: LocationCode? = null
    override val to: LocationCode = toLocation
    override val effect: StockEffect = StockEffect.LeaveTransit(toLocation, fromWarehouseId)
}

/**
 * Intención de salida: reserva mercancía sin sacarla.
 * **No cambia el inventario.** Declararlo como efecto explícito evita el caso
 * especial escondido que tenía el legacy.
 */
data class PendingOut(
    override val quantity: Quantity,
    val location: LocationCode,
    val orderNumber: String,
) : StockMovement {
    override val type = MovementType.PENDING_OUT
    override val from: LocationCode? = location
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.NoInventoryChange
}

// ─────────────────────────────────────────────────────────────────────────────
// Operaciones derivadas del efecto
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Indica si el movimiento **cambia el inventario total**.
 *
 * Es la consulta que el legacy no podía hacer: no había forma de saber si un
 * movimiento afectaba al total sin leer un `if/else` de 15 líneas.
 */
val StockEffect.changesTotal: Boolean
    get() = when (this) {
        is StockEffect.AddToLocation -> true
        is StockEffect.RemoveFromLocation -> true
        is StockEffect.TransferWithinWarehouse -> false  // invariante
        is StockEffect.EnterTransit -> false             // sigue en el inventario
        is StockEffect.LeaveTransit -> false             // sale del tránsito, entra al estante
        StockEffect.NoInventoryChange -> false
    }

/**
 * Delta del total de inventario que produce este movimiento.
 *
 * **Nótese que solo dos efectos pueden devolver algo distinto de cero**, y uno
 * es positivo y el otro negativo. No existe ninguna combinación que reste dos
 * veces. Esa es la garantía contra el bug del legacy.
 */
fun StockEffect.totalDelta(quantity: Quantity): Long = when (this) {
    is StockEffect.AddToLocation -> quantity.value
    is StockEffect.RemoveFromLocation -> -quantity.value
    is StockEffect.TransferWithinWarehouse -> 0L
    is StockEffect.EnterTransit -> 0L
    is StockEffect.LeaveTransit -> 0L
    StockEffect.NoInventoryChange -> 0L
}

/**
 * Ubicaciones afectadas y su delta. Es lo que la capa de persistencia aplica
 * sobre `stock_by_location`.
 *
 * Devuelve un mapa porque un mismo movimiento puede tocar dos ubicaciones
 * (traslado) o una ubicación y el tránsito.
 */
fun StockEffect.locationDeltas(quantity: Quantity): Map<LocationCode, Long> = when (this) {
    is StockEffect.AddToLocation -> mapOf(location to quantity.value)
    is StockEffect.RemoveFromLocation -> mapOf(location to -quantity.value)
    is StockEffect.TransferWithinWarehouse ->
        mapOf(from to -quantity.value, to to quantity.value)
    is StockEffect.EnterTransit -> mapOf(from to -quantity.value)
    is StockEffect.LeaveTransit -> mapOf(to to quantity.value)
    StockEffect.NoInventoryChange -> emptyMap()
}
