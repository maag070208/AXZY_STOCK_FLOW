package dev.axzy.stock.domain.error

import kotlinx.serialization.Serializable

/**
 * Errores del dominio.
 *
 * Se modelan como un tipo sellado en lugar de excepciones por tres razones:
 *
 *  1. **Son parte del contrato**, no un accidente: "la cantidad excede la
 *     factura" es un resultado de negocio esperado, no un fallo del programa.
 *  2. **Obligan a manejarlos**: un `when` exhaustivo sobre [DomainError] no
 *     compila si aparece un caso nuevo sin tratar.
 *  3. **Se pueden mapear a códigos HTTP** sin perder información, y cada uno
 *     tiene un código estable que el móvil y la web usan para decidir qué
 *     mensaje mostrar. Un "acceso denegado" genérico no es accionable; "no
 *     tienes permiso sobre esta bodega" sí.
 *
 * Referencia: PLAN_ALMACEN_INTEGRAL.md §4 (códigos de error) y
 * PLAN_REFACTOR_MEJORAS.md R-20 (el legacy devolvía HTTP 200 en errores de
 * negocio y 500 con cuerpo `{}`).
 */
@Serializable
sealed interface DomainError {

    /** Código estable para el cliente. No cambia entre versiones. */
    val code: String

    /** Mensaje legible. Es un detalle de presentación; el cliente usa [code]. */
    val message: String

    // ── Transiciones de estado ──────────────────────────────────────────────

    /**
     * La transición pedida no es válida desde el estado actual.
     * El legacy permitía saltos ilegales porque los estados eran strings libres.
     */
    data class InvalidTransition(
        val entity: String,
        val from: String,
        val action: String,
        override val message: String = "No se puede '$action' desde el estado '$from'",
    ) : DomainError {
        override val code = "INVALID_TRANSITION"
    }

    // ── Cantidades ──────────────────────────────────────────────────────────

    /** Se intentó ubicar o surtir más de lo que la línea admite. */
    data class ExceedsLineQuantity(
        val requested: Long,
        val available: Long,
        override val message: String =
            "La cantidad solicitada excede lo disponible en la línea",
    ) : DomainError {
        override val code = "EXCEEDS_LINE_QUANTITY"
    }

    /**
     * La cantidad y las unidades por caja no cuadran (decisión R-80).
     * Una factura de 100 piezas con cajas de 30 es siempre un dato mal capturado.
     */
    data class BoxesDoNotDivideEvenly(
        val quantity: Long,
        val unitsPerBox: Long,
        val remainder: Long,
        override val message: String =
            "La cantidad no es divisible entre las unidades por caja: " +
                "quedaría un remanente de $remainder",
    ) : DomainError {
        override val code = "BOXES_DO_NOT_DIVIDE_EVENLY"
    }

    /**
     * Las cajas declaradas no caben en la cantidad declarada.
     * Ejemplo: 80 piezas con 2 cajas de 50 (serían 100).
     */
    data class InconsistentBoxCount(
        val quantity: Long,
        val boxes: Long,
        val unitsPerBox: Long,
        override val message: String =
            "$boxes caja(s) de $unitsPerBox no caben en $quantity unidades",
    ) : DomainError {
        override val code = "INCONSISTENT_BOX_COUNT"
    }

    /** Cantidad negativa o cero donde se exige positiva. */
    data class InvalidQuantity(
        override val message: String,
    ) : DomainError {
        override val code = "INVALID_QUANTITY"
    }

    // ── Discrepancias y aprobación ──────────────────────────────────────────

    /**
     * La línea tiene una discrepancia sin aprobar.
     *
     * Es el error que hace efectiva la regla B1: **no se puede ubicar
     * mercancía cuya diferencia con la factura no haya sido autorizada**.
     * En el legacy este control no existía.
     */
    data class DiscrepancyNotApproved(
        val difference: Long,
        override val message: String =
            "La línea tiene una diferencia de $difference unidades sin aprobar. " +
                "Un supervisor debe autorizarla antes de ubicar la mercancía.",
    ) : DomainError {
        override val code = "DISCREPANCY_NOT_APPROVED"
    }

    /**
     * Quien capturó intenta aprobar su propia discrepancia (decisión R-74).
     *
     * Es el control interno más importante del flujo de recepción: evita que
     * alguien tape su propio error de conteo.
     */
    data class SegregationOfDuties(
        override val message: String =
            "No puedes aprobar una discrepancia que tú mismo capturaste. " +
                "Requiere la autorización de otra persona.",
    ) : DomainError {
        override val code = "SEGREGATION_OF_DUTIES"
    }

    /** Se intentó aprobar algo que no está en estado de aprobación. */
    data class NotPendingApproval(
        val currentStatus: String,
        override val message: String =
            "La línea no está pendiente de aprobación (estado actual: $currentStatus)",
    ) : DomainError {
        override val code = "NOT_PENDING_APPROVAL"
    }

    /** Se exige un motivo y no se proporcionó (invariante I6). */
    data class ReasonRequired(
        val context: String,
        override val message: String = "Se requiere un motivo para $context",
    ) : DomainError {
        override val code = "REASON_REQUIRED"
    }

    // ── Inventario ──────────────────────────────────────────────────────────

    /** La operación dejaría stock negativo (invariante I2). */
    data class InsufficientStock(
        val available: Long,
        val requested: Long,
        val location: String,
        override val message: String =
            "Stock insuficiente en $location: hay $available y se piden $requested",
    ) : DomainError {
        override val code = "INSUFFICIENT_STOCK"
    }

    /**
     * El origen y el destino de un movimiento son iguales.
     * En el legacy esto no se validaba y generaba movimientos sin efecto.
     */
    data class SameSourceAndDestination(
        val location: String,
        override val message: String = "El origen y el destino no pueden ser la misma ubicación",
    ) : DomainError {
        override val code = "SAME_SOURCE_AND_DESTINATION"
    }

    // ── Documentos ──────────────────────────────────────────────────────────

    /** El documento tiene líneas pendientes y no se puede cerrar. */
    data class HasPendingLines(
        val pendingCount: Int,
        override val message: String =
            "El documento tiene $pendingCount línea(s) sin resolver y no se puede cerrar",
    ) : DomainError {
        override val code = "HAS_PENDING_LINES"
    }

    /**
     * Se intentó borrar un documento con efecto en inventario.
     * No se borra: se **anula** con un movimiento compensatorio (R-57 y R-68).
     */
    data class CannotDeleteWithInventoryEffect(
        override val message: String =
            "Este documento afecta al inventario y no se puede eliminar: " +
                "debe anularse con un movimiento compensatorio.",
    ) : DomainError {
        override val code = "CANNOT_DELETE_WITH_INVENTORY_EFFECT"
    }

    /** Conflicto de unicidad (número de factura u orden ya existe). */
    data class Duplicate(
        val entity: String,
        val value: String,
        override val message: String = "Ya existe $entity con el valor '$value'",
    ) : DomainError {
        override val code = "DUPLICATE"
    }

    /** El recurso no existe. */
    data class NotFound(
        val entity: String,
        val id: String,
        override val message: String = "No se encontró $entity con id '$id'",
    ) : DomainError {
        override val code = "NOT_FOUND"
    }

    // ── Acceso (detalle en PLAN_ROLES_PERMISOS_POLITICAS.md) ────────────────

    /** RBAC no alcanza: el usuario no tiene el permiso. */
    data class PermissionDenied(
        val permission: String,
        override val message: String = "No tienes permiso para esta acción",
    ) : DomainError {
        override val code = "PERMISSION_DENIED"
    }

    /** Tiene el permiso, pero no para **ese** recurso concreto. */
    data class ScopeInsufficient(
        val permission: String,
        val scope: String,
        override val message: String =
            "No tienes permiso sobre este recurso (alcance: $scope)",
    ) : DomainError {
        override val code = "SCOPE_INSUFFICIENT"
    }

    /** Una política ABAC denegó la acción. */
    data class PolicyDenied(
        val policyName: String,
        override val message: String = "Denegado por la política '$policyName'",
    ) : DomainError {
        override val code = "POLICY_DENIED"
    }

    /**
     * Hay políticas aplicables y ninguna `ALLOW` coincidió.
     * Es lo que hace que ABAC suba el listón en lugar de bajarlo.
     */
    data class PolicyRequired(
        override val message: String = "Esta acción requiere autorización específica",
    ) : DomainError {
        override val code = "POLICY_REQUIRED"
    }
}
