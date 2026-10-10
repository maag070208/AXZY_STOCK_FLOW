package dev.axzy.stock.domain.receiving

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MÁQUINA DE ESTADOS DE LA LÍNEA DE FACTURA
 *
 * Es el artefacto 2 de los cuatro críticos. Aquí viven tres reglas de negocio
 * que el legacy no tenía (o tenía mal), y las tres están confirmadas contigo:
 *
 *   · **B1 / R-75 — Toda discrepancia bloquea.** Sin umbral: cualquier
 *     diferencia con la factura impide ubicar la mercancía hasta que alguien
 *     la autorice. En el legacy esto no existía y la mercancía se ubicaba con
 *     la diferencia sin resolver.
 *
 *   · **R-74 — Segregación de funciones.** Quien capturó **no puede aprobar**
 *     su propia discrepancia. Evita que alguien tape su propio error de conteo.
 *
 *   · **R-80 — Las cajas deben cuadrar.** Si `cantidad / unidadesPorCaja` no
 *     da entero, la captura se rechaza. Una factura de 100 piezas con cajas de
 *     30 es siempre un dato mal capturado, no un caso a manejar.
 *
 * **El diseño hace imposibles los saltos ilegales**: no hay un `status` que se
 * pueda asignar a mano, solo funciones de transición que validan el estado de
 * origen. En el legacy, los estados eran strings libres y `invoiceDetail.status
 * = 'done'` se escribía desde cualquier sitio (hallazgo L1 y B-3).
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Estado de una línea de factura.
 *
 * Nombres en inglés para el enum (consistencia con el resto del modelo y con
 * los valores de la base de datos), documentados en español.
 */
enum class InvoiceLineStatus {
    /** Alta de la línea: la mercancía aún no se ha contado. */
    PENDING,

    /** Contada y la cantidad **coincide** con la factura. */
    CAPTURED,

    /** Contada y la cantidad **NO coincide**: requiere aprobación. */
    DISCREPANCY,

    /**
     * Un supervisor la revisó y autorizó el ajuste.
     * Es el paso que añade el sistema nuevo: sin él no se puede ubicar.
     */
    APPROVED,

    /** Trabajo asignado a un operador (etapa previa a la captura). */
    ASSIGNED,

    /** Mercancía ubicada en un estante, total o parcialmente. */
    LOCATED,

    /** Línea anulada. Nunca se borra: se anula con trazabilidad. */
    CANCELLED,
    ;

    /** Indica si la línea permite ubicar mercancía. */
    val allowsLocating: Boolean
        get() = when (this) {
            CAPTURED, APPROVED, ASSIGNED, LOCATED -> true
            PENDING, DISCREPANCY, CANCELLED -> false
        }

    /**
     * Indica si la línea impide cerrar la factura.
     * Es la consulta que el legacy respondía con un `filter` de strings sueltos.
     */
    val blocksInvoiceClosing: Boolean
        get() = when (this) {
            PENDING, DISCREPANCY, ASSIGNED -> true
            CAPTURED, APPROVED, LOCATED, CANCELLED -> false
        }
}

/**
 * Una línea de factura con su estado y sus cantidades.
 *
 * Inmutable: cada transición devuelve una línea nueva. Eso hace que el estado
 * sea siempre consistente y que las transiciones se puedan probar sin mocks.
 */
data class InvoiceLine(
    val id: Long,
    val productCode: ProductCode,
    /** Cantidad según la factura del proveedor. */
    val invoicedQuantity: Quantity,
    /** Unidades por caja. No puede ser cero (lo impide el tipo). */
    val unitsPerBox: UnitsPerBox,
    val status: InvoiceLineStatus,
    /** Cantidad contada físicamente. `null` mientras no se haya contado. */
    val physicalQuantity: Quantity? = null,
    /** Cajas contadas físicamente. */
    val physicalBoxQuantity: BoxQuantity? = null,
    /** Unidades ya ubicadas en estantes. */
    val locatedQuantity: Quantity = Quantity.ZERO,
    /** Operador que hizo la captura física. Necesario para R-74. */
    val capturedBy: Long? = null,
    /** Supervisor que autorizó la discrepancia. Necesario para la auditoría. */
    val approvedBy: Long? = null,
    /** Motivo del ajuste autorizado (invariante I6). */
    val adjustmentReason: String? = null,
    /** Ubicación de destino. */
    val location: LocationCode? = null,
    /**
     * Unidades ya **ingresadas** en `RECEPCION` por esta línea.
     *
     * Es lo que hace que contar dos veces no sume dos veces: la entrada se escribe por la
     * diferencia entre lo contado ahora y lo que ya se ingresó. Existe porque una línea con
     * discrepancia que el supervisor descarta vuelve a `PENDING` y se recuenta
     * ([rejectDiscrepancy]), y sin este número el kardex contaría la mercancía dos veces.
     */
    val receivedQuantity: Quantity = Quantity.ZERO,
    /**
     * Quién tiene la línea asignada para contarla.
     *
     * La columna existe en la base desde la primera migración y **nadie la escribía**: la ruta de
     * asignación era la que faltaba. El estado `ASSIGNED` solo decía «alguien la tiene», sin
     * decir quién.
     */
    val assignedUserId: Long? = null,
) {

    /**
     * Diferencia entre lo contado y lo facturado.
     *
     * **Este es el campo que el legacy declaraba en modelo, DTO, mapper y
     * respuesta y siempre devolvía `0`** (hallazgo R-58 / L1). Aquí es una
     * propiedad derivada: no hay forma de que se desincronice porque no se
     * almacena, se calcula.
     *
     * Positivo = llegó más de lo facturado. Negativo = llegó menos.
     */
    val difference: Quantity?
        get() = physicalQuantity?.let { it - invoicedQuantity }

    /** Indica si hay una diferencia sin resolver. */
    val hasUnresolvedDifference: Boolean
        get() = status == InvoiceLineStatus.DISCREPANCY

    /**
     * Unidades pendientes de ubicar.
     * Diferencia entre lo contado (o facturado si aún no se contó) y lo ubicado.
     */
    val pendingToLocate: Quantity
        get() = (physicalQuantity ?: invoicedQuantity) - locatedQuantity

    // ═════════════════════════════════════════════════════════════════════════
    // TRANSICIONES
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Captura física de la línea.
     *
     * Reglas aplicadas:
     *  · Solo se puede capturar desde `PENDING` o `ASSIGNED`.
     *  · La cantidad física debe ser positiva.
     *  · **Las cajas deben cuadrar exactamente** (R-80).
     *  · Si `física == factura` → `CAPTURED`; si difiere → `DISCREPANCY`.
     *
     * @return línea nueva, o el error de dominio correspondiente.
     */
    fun capture(
        physicalQuantity: Quantity,
        physicalBoxQuantity: BoxQuantity,
        capturedBy: Long,
    ): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.PENDING && status != InvoiceLineStatus.ASSIGNED) {
            return DomainError.InvalidTransition(
                entity = "línea de factura",
                from = status.name,
                action = "capturar",
            ).asLeft()
        }

        if (!physicalQuantity.isPositive) {
            return DomainError.InvalidQuantity(
                "La cantidad contada debe ser mayor que cero (se contaron $physicalQuantity)"
            ).asLeft()
        }

        // NOTA DE DISEÑO: aquí NO se valida que la cantidad contada divida exacto
        // entre las unidades por caja. Contar 80 piezas de una factura de 100 con
        // cajas de 50 significa "1 caja y 30 sueltas": es un FALTANTE legítimo,
        // no un dato mal capturado. Validarlo aquí bloquearía exactamente el caso
        // que el flujo de discrepancia existe para resolver.
        //
        // La validación de cajas es sobre la ESPECIFICACIÓN de la factura y se
        // hace al crear la línea (ver validatePackingSpecification).

        // La caja contada debe corresponder con la cantidad contada: si se
        // cuentan 80 piezas no pueden ser 2 cajas de 50 (serían 100).
        if (!physicalBoxQuantity.isConsistentWith(physicalQuantity, unitsPerBox)) {
            return DomainError.InconsistentBoxCount(
                quantity = physicalQuantity.units,
                boxes = physicalBoxQuantity.value,
                unitsPerBox = unitsPerBox.value,
            ).asLeft()
        }

        val matchesInvoice = physicalQuantity == invoicedQuantity
        return copy(
            physicalQuantity = physicalQuantity,
            physicalBoxQuantity = physicalBoxQuantity,
            capturedBy = capturedBy,
            status = if (matchesInvoice) InvoiceLineStatus.CAPTURED else InvoiceLineStatus.DISCREPANCY,
        ).asRight()
    }

    /**
     * Aprueba una discrepancia y ajusta la cantidad de la línea a lo contado.
     *
     * **La regla de segregación de funciones (R-74) se aplica aquí**: si quien
     * aprueba es la misma persona que capturó, se devuelve
     * [DomainError.SegregationOfDuties] y no se modifica nada.
     *
     * El motivo ([reason]) es obligatorio: el ajuste mueve inventario y sin
     * motivo es un agujero de auditoría (invariante I6).
     */
    fun approveDiscrepancy(
        approvedBy: Long,
        reason: String,
    ): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.DISCREPANCY) {
            return DomainError.NotPendingApproval(status.name).asLeft()
        }

        if (reason.isBlank()) {
            return DomainError.ReasonRequired("autorizar una discrepancia de inventario").asLeft()
        }

        // ── Segregación de funciones (R-74) ─────────────────────────────────
        // El control interno más importante del flujo: nadie firma su propio
        // error de conteo. Si el negocio algún día decide relajarlo, se hace
        // desactivando una política, no borrando este código.
        if (capturedBy != null && capturedBy == approvedBy) {
            return DomainError.SegregationOfDuties().asLeft()
        }

        return copy(
            // La cantidad facturada se ajusta a la realidad contada: es lo que
            // autoriza el supervisor al aprobar.
            invoicedQuantity = physicalQuantity ?: invoicedQuantity,
            approvedBy = approvedBy,
            adjustmentReason = reason,
            status = InvoiceLineStatus.APPROVED,
        ).asRight()
    }

    /**
     * Rechaza la discrepancia y devuelve la línea a captura.
     * El contador de cajas y la cantidad se limpian para forzar un reconteo.
     */
    fun rejectDiscrepancy(reason: String): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.DISCREPANCY) {
            return DomainError.NotPendingApproval(status.name).asLeft()
        }
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("rechazar una discrepancia").asLeft()
        }

        return copy(
            physicalQuantity = null,
            physicalBoxQuantity = null,
            capturedBy = null,
            status = InvoiceLineStatus.PENDING,
        ).asRight()
    }

    /**
     * Ubica mercancía en un estante.
     *
     * **Aquí es donde la regla B1 se hace efectiva**: si la línea tiene una
     * discrepancia sin aprobar, no se puede ubicar. El error devuelto incluye
     * la diferencia para que la UI pueda explicar cuánto falta autorizar.
     */
    fun locate(
        quantity: Quantity,
        toLocation: LocationCode? = null,
    ): DomainResult<InvoiceLine> {
        // ── Regla B1: discrepancia bloquea la ubicación ─────────────────────
        if (status == InvoiceLineStatus.DISCREPANCY) {
            return DomainError.DiscrepancyNotApproved(
                difference = difference?.value ?: 0L,
            ).asLeft()
        }

        if (status == InvoiceLineStatus.CANCELLED) {
            return DomainError.InvalidTransition("línea de factura", status.name, "ubicar").asLeft()
        }

        if (status == InvoiceLineStatus.PENDING) {
            return DomainError.InvalidTransition(
                entity = "línea de factura",
                from = status.name,
                action = "ubicar",
                message = "La mercancía debe contarse antes de ubicarse",
            ).asLeft()
        }

        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("La cantidad a ubicar debe ser mayor que cero").asLeft()
        }

        val pending = pendingToLocate
        if (quantity > pending) {
            return DomainError.ExceedsLineQuantity(
                requested = quantity.value,
                available = pending.value,
                message = "No se pueden ubicar $quantity unidades: " +
                    "solo quedan $pending pendientes de ubicar",
            ).asLeft()
        }

        val newLocated = locatedQuantity + quantity
        val target = physicalQuantity ?: invoicedQuantity
        /*
         * Ubicar **parcialmente** no aprueba nada: se conserva el estado.
         *
         * Antes pasaba a `APPROVED`, y eso era un error por dos razones: una línea
         * contada que coincide con la factura no tiene ninguna discrepancia que
         * aprobar, y la base prohíbe `APPROVED` sin `approved_by` (lo impone el
         * `CHECK invoice_lines_approved_requires_approver`), así que la escritura
         * habría fallado. El estado correcto de «falta ubicar el resto» es el que ya
         * tenía: `CAPTURED` si se contó, `APPROVED` si un supervisor ya la autorizó.
         */
        val complete = newLocated == target
        return copy(
            locatedQuantity = newLocated,
            location = toLocation ?: location,
            status = if (complete) InvoiceLineStatus.LOCATED else status,
        ).asRight()
    }

    /** Anula la línea. Nunca se borra: se marca y se conserva el rastro. */
    fun cancel(reason: String): DomainResult<InvoiceLine> {
        if (status == InvoiceLineStatus.LOCATED) {
            return DomainError.CannotDeleteWithInventoryEffect(
                message = "No se puede anular una línea con mercancía ya ubicada. " +
                    "Debe registrarse un movimiento de ajuste con motivo.",
            ).asLeft()
        }
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("anular una línea de factura").asLeft()
        }
        return copy(status = InvoiceLineStatus.CANCELLED).asRight()
    }

    /**
     * Asigna la línea a un operador para que la cuente.
     *
     * **Solo desde `PENDING`.** Una línea ya contada no se reasigna: el conteo tiene responsable
     * ([capturedBy]) y cambiarlo después sería reescribir quién hizo el trabajo. Tampoco desde
     * `DISCREPANCY`: esa línea espera una decisión, no un capturista.
     */
    fun assign(userId: Long): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.PENDING) {
            return DomainError.InvalidTransition("línea de factura", status.name, "asignar").asLeft()
        }
        return copy(status = InvoiceLineStatus.ASSIGNED, assignedUserId = userId).asRight()
    }

    companion object {

        /**
         * Decisión R-80, aplicada en el sitio correcto: **al crear la línea**.
         *
         * La especificación de empaque de una factura debe ser coherente: si
         * dice 100 piezas y las cajas son de 30, quedan 10 sueltas y el dato es
         * imposible (nadie empaca una fracción de caja). Se rechaza el alta.
         *
         * **NO se aplica a la cantidad contada.** Contar 80 de 100 con cajas de
         * 50 es un faltante legítimo (1 caja + 30 sueltas) y es precisamente lo
         * que el flujo de discrepancia debe capturar, no bloquear.
         *
         * Excepción: cantidades menores que una caja son mercancía suelta y son
         * válidas (20 piezas con cajas de 30).
         */
        fun validatePackingSpecification(
            invoicedQuantity: Quantity,
            unitsPerBox: UnitsPerBox,
        ): DomainResult<Unit> {
            if (invoicedQuantity.isZero) return Unit.asRight()

            val completeBoxes = invoicedQuantity.units / unitsPerBox.value
            val remainder = invoicedQuantity.units % unitsPerBox.value

            return if (completeBoxes > 0L && remainder > 0L) {
                DomainError.BoxesDoNotDivideEvenly(
                    quantity = invoicedQuantity.units,
                    unitsPerBox = unitsPerBox.value,
                    remainder = remainder,
                ).asLeft()
            } else {
                Unit.asRight()
            }
        }
    }
}
