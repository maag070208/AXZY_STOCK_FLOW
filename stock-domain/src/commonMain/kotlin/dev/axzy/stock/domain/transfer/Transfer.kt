package dev.axzy.stock.domain.transfer

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Id
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MÁQUINA DE ESTADOS DEL TRASLADO ENTRE BODEGAS (contrato §7)
 *
 * ```
 *   DRAFT ──dispatch──> DISPATCHED ──receive──> RECEIVED
 *     │                    │
 *     │                    └──(en tránsito hasta que se reciba)
 *     └──cancel──> CANCELLED <──cancel──┘
 * ```
 *
 * ── LA INVARIANTE QUE DEFINE TODO EL DISEÑO ────────────────────────────────
 *
 * > **`Σ stock_by_location + Σ stock_in_transit` se conserva en CADA
 * > transición.**
 *
 * La mercancía en el camión **sigue contando** en el inventario. Si el traslado
 * se modelara como dos movimientos instantáneos —sale de A, entra en B— el total
 * del producto bajaría durante el viaje y **los reportes mentirían**: un informe
 * de existencias a mitad de un traslado enseñaría menos mercancía de la que hay.
 * Y peor: el descuadre aparecería y desaparecería solo, sin que nadie pudiera
 * explicarlo.
 *
 * Es la razón de que `stock_in_transit` exista como tabla desde V3 y de que
 * `recalculate_product_total` (V6) la sume.
 *
 * ── POR QUÉ EL ESTADO NO ES UN `String` ────────────────────────────────────
 *
 * En el legacy los estados eran cadenas libres y **cualquier sitio podía escribir
 * cualquiera**: `invoice.status = 'done'` desde un controlador, desde un script
 * de mantenimiento o desde una consulta a mano. Aquí las transiciones son las
 * ÚNICAS funciones que cambian el estado, y cada una valida el estado de origen.
 * Un salto ilegal —recibir un traslado que nunca se despachó— **no compila**
 * porque no hay una función que lo haga.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Estado de un traslado. Los valores coinciden con el enum `transfer_status`. */
enum class TransferStatus {
    /** Creado y todavía editable. La mercancía NO se ha movido. */
    DRAFT,

    /** Despachado: salió de las ubicaciones de origen y entró en tránsito. */
    DISPATCHED,

    /**
     * En tránsito.
     *
     * **Estado distinto de `DISPATCHED` a propósito**, aunque el flujo normal
     * pase de uno al otro sin intervención: son dos hechos distintos —«salió» y
     * «va por el camino»— y el informe de tránsito los necesita separados para
     * poder decir cuántos días lleva cada uno.
     */
    IN_TRANSIT,

    /** Recibido en la bodega de destino: la mercancía está en sus ubicaciones. */
    RECEIVED,

    /** Cancelado. Solo desde `DRAFT` o `DISPATCHED`: lo recibido se corrige con
     *  un movimiento, no cancelando el pasado. */
    CANCELLED,
    ;

    /** ¿La mercancía de este traslado está fuera de las ubicaciones? */
    val isInTransit: Boolean
        get() = this == DISPATCHED || this == IN_TRANSIT

    /** ¿Se puede todavía cambiar el contenido del traslado? */
    val isEditable: Boolean
        get() = this == DRAFT
}

/** Una línea del traslado: un producto y cuánto viaja. */
data class TransferLine(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val quantity: Quantity,
    /** Ubicación de origen de la que sale. `null` = la de recepción/sin ubicar. */
    val fromLocationId: Long? = null,
    val fromLocationDisplayName: String? = null,
    val note: String? = null,
)

/**
 * Un traslado con su cabecera y sus líneas.
 *
 * Inmutable: cada transición devuelve un traslado NUEVO. Así el estado es siempre
 * consistente y las transiciones se prueban sin base de datos ni dobles de
 * prueba — que es lo que hace que estas reglas se puedan probar en milisegundos
 * y las mismas en el backend y en el móvil.
 */
data class Transfer(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val toWarehouseId: Long,
    val status: TransferStatus,
    val lines: List<TransferLine> = emptyList(),
    val note: String? = null,
    val dispatchedBy: Long? = null,
    val receivedBy: Long? = null,
) {

    /** Cuántos productos distintos viajan. */
    val lineCount: Int get() = lines.size

    /** La suma de las cantidades. */
    val totalUnits: Quantity
        get() = lines.fold(Quantity.ZERO) { acc, linea -> acc + linea.quantity }

    /**
     * Marca el traslado como despachado: la mercancía sale de las ubicaciones de
     * origen y entra en tránsito.
     *
     * **Lo que NO hace esta función y es deliberado: mover el stock.** El dominio
     * decide SI la transición es legal y QUIÉN la hace; el movimiento de las
     * existencias lo ejecuta el motor SQL en la misma transacción que escribe el
     * kardex y el tránsito (V6). Separarlo sería justo lo que descuadra un
     * inventario: si el dominio moviera el stock y la escritura del kardex
     * fallara, la mercancía habría cambiado de sitio sin registro.
     */
    fun dispatch(userId: Long): Either<DomainError, Transfer> {
        if (lines.isEmpty()) return DomainError.EmptyTransfer().asLeft()

        val isValid = status == TransferStatus.DRAFT
        if (!isValid) {
            return DomainError.InvalidTransition(
                entity = "Traslado",
                from = status.name,
                action = "despachar",
            ).asLeft()
        }

        return copy(
            status = TransferStatus.DISPATCHED,
            dispatchedBy = userId,
        ).asRight()
    }

    /**
     * Marca el traslado como recibido en la bodega de destino.
     *
     * Solo desde `DISPATCHED` o `IN_TRANSIT`: **recibir algo que nunca se
     * despachó** sería dar por llegada una mercancía que nunca salió, y el
     * inventario de destino crecería sin que el de origen hubiera bajado.
     */
    fun receive(userId: Long): Either<DomainError, Transfer> {
        if (!status.isInTransit) {
            return DomainError.InvalidTransition(
                entity = "Traslado",
                from = status.name,
                action = "recibir",
            ).asLeft()
        }

        return copy(
            status = TransferStatus.RECEIVED,
            receivedBy = userId,
        ).asRight()
    }

    /**
     * Cancela el traslado.
     *
     * **Desde `RECEIVED` NO se puede.** Lo recibido está en las ubicaciones de
     * destino y cancelarlo dejaría la mercancía contada en el sitio equivocado:
     * si hay que deshacerlo, se hace con un traslado de vuelta, que deja rastro.
     * Un `CANCELLED` sobre algo ya recibido sería un borrado del pasado.
     */
    fun cancel(): Either<DomainError, Transfer> {
        val cancelable = status == TransferStatus.DRAFT || status == TransferStatus.DISPATCHED
        if (!cancelable) {
            return DomainError.InvalidTransition(
                entity = "Traslado",
                from = status.name,
                action = "cancelar",
            ).asLeft()
        }

        return copy(status = TransferStatus.CANCELLED).asRight()
    }

    companion object {
        /**
         * Valida las dos reglas que no dependen del estado, antes de crear nada.
         *
         * Se comprueban ANTES de tocar la base para poder dar un `422` con el
         * campo señalado en lugar de traducir un error de restricción, que no
         * dice qué campo está mal.
         */
        fun validate(
            fromWarehouseId: Long,
            toWarehouseId: Long,
            lines: List<TransferLine>,
        ): Either<DomainError, Unit> {
            if (fromWarehouseId == toWarehouseId) return DomainError.SameWarehouse().asLeft()
            if (lines.isEmpty()) return DomainError.EmptyTransfer().asLeft()
            return Unit.asRight()
        }
    }
}

/** El identificador tipado de un traslado, para no confundirlo con otro `Long`. */
typealias TransferId = Id<Transfer>
