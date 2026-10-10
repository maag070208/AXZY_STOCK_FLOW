package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA REGLA ÚNICA DE AJUSTE DE INVENTARIO
 *
 * **Ésta es la pieza que el legacy tenía duplicada cuatro veces.** Cada flujo que
 * necesitaba mover stock por una razón que no fuera una recepción o un surtido
 * tenía su propio camino:
 *
 *  · la discrepancia de factura restaba en un sitio,
 *  · el faltante de surtido restaba en otro,
 *  · el conteo físico corregía en un tercero,
 *  · y el reporte de daños en un cuarto.
 *
 * Cuatro caminos significan cuatro oportunidades de olvidarse del motivo, de no
 * comprobar el saldo, o de no dejarlo en el kardex. **Aquí hay uno solo**, y los
 * cuatro orígenes son un campo del mismo movimiento.
 *
 * Tres reglas que se imponen aquí y no en cada endpoint:
 *
 *  1. **El motivo es obligatorio, siempre.** Un ajuste sin motivo es un agujero de
 *     auditoría: dentro de un año nadie puede explicar por qué faltan 200 piezas.
 *     No hay ninguna excepción, ni siquiera para el sistema.
 *
 *  2. **Un ajuste que AUMENTA el inventario exige aprobación.** Una merma se
 *     detecta sola en el siguiente conteo; un aumento sin justificar es la forma
 *     más sencilla de tapar un robo, así que necesita que alguien más lo firme.
 *
 *  3. **Quien ajusta no aprueba lo suyo** (segregación de funciones, decisión
 *     R-74). Es el control interno que impide que una persona cubra su propio
 *     error sin que nadie lo revise.
 *
 * Referencia: docs/historico/PLAN_ALMACEN_INTEGRAL.md §2.9 y las decisiones B2, B3 y R-74.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Por qué se ajusta el inventario.
 *
 * **Lista cerrada y con significado operativo.** El legacy guardaba el motivo como
 * texto libre, así que convivían «merma», «Merma» y «producto dañado» como tres
 * motivos distintos, y ningún reporte podía sumarlos.
 */
enum class AdjustmentReason(val displayName: String, val description: String) {
    SHORTAGE(
        "Faltante",
        "Falta mercancía respecto a lo que debería haber. Se detecta en un conteo.",
    ),
    DAMAGE(
        "Daño",
        "La mercancía se dañó y no se puede vender. Se retira del inventivo disponible.",
    ),
    THEFT(
        "Robo",
        "Se detectó un faltante con indicios de robo. **Exige motivo escrito**: " +
            "tiene implicaciones legales y alguien tendrá que responder por él.",
    ),
    SUPPLIER_ERROR(
        "Error del proveedor",
        "El proveedor envió menos o distinto de lo facturado.",
    ),
    COUNT_ERROR(
        "Error de conteo",
        "El saldo anterior estaba mal por un error de captura, no por una pérdida real.",
    ),
    RETURN_TO_SUPPLIER(
        "Devolución a proveedor",
        "La mercancía sale del almacén de vuelta al proveedor.",
    ),
    EXPIRED(
        "Caducado",
        "La mercancía superó su vida útil y se retira.",
    ),
    OTHER(
        "Otro",
        "**Exige motivo escrito.** Si se usa con frecuencia, falta un motivo en la " +
            "lista: un «otro» repetido es una señal de que la taxonomía está mal.",
    ),
    ;

    /** Indica si el motivo obliga a escribir una explicación además de elegirlo. */
    val requiresNote: Boolean get() = this == THEFT || this == OTHER
}

/**
 * De dónde viene el ajuste.
 *
 * Es lo que permite responder «¿cuánto se ajustó por conteo físico y cuánto por
 * daños?» sin interpretar textos. Los cuatro orígenes del legacy unificados aquí.
 */
enum class AdjustmentOrigin(val displayName: String) {
    INVOICE_DISCREPANCY("Discrepancia de factura"),
    FULFILLMENT_SHORTAGE("Faltante en surtido"),
    MANUAL_COUNT("Conteo físico"),
    DAMAGE_REPORT("Reporte de daño"),
    ;
}

/** Hacia dónde va el ajuste. */
enum class AdjustmentDirection {
    /** Aumenta el inventario. Exige aprobación. */
    INCREASE,

    /** Disminuye el inventario. */
    DECREASE,
    ;

    /**
     * Traduce a un movimiento del ledger.
     *
     * **El ajuste no inventa un tipo nuevo**: usa `ADJUSTMENT_IN` o
     * `ADJUSTMENT_OUT`, que ya existen en la matriz movimiento→efecto con sus
     * tests. Un tipo nuevo obligaría a declarar su efecto en todas partes.
     */
    val movementType: MovementType
        get() = when (this) {
            INCREASE -> MovementType.ADJUSTMENT_IN
            DECREASE -> MovementType.ADJUSTMENT_OUT
        }
}

/**
 * Una solicitud de ajuste, ya validada.
 *
 * Se construye solo por [AdjustmentRequest.create], así que **es imposible que
 * exista una solicitud sin motivo**: el tipo no lo permite.
 */
data class AdjustmentRequest private constructor(
    val productCode: ProductCode,
    /**
     * La ubicación, por su **nombre visible** (`04-B-C`, `RECEPCION`, `SIN-UBICAR`).
     *
     * No es un [dev.axzy.stock.value.LocationCode]: aquel objeto de valor es el
     * código plano del legacy (`04BCA`) y no puede representar ni la recepción ni el
     * saldo sin ubicar. Aquí solo hace falta nombrar la ubicación en un mensaje, y
     * el nombre visible es el que el operador reconoce.
     */
    val locationLabel: String,
    val quantity: Quantity,
    val direction: AdjustmentDirection,
    val reason: AdjustmentReason,
    val origin: AdjustmentOrigin,
    /** Obligatorio en [AdjustmentReason.THEFT] y [AdjustmentReason.OTHER]. */
    val note: String?,
    /** Quién lo pide. Necesario para la segregación de funciones. */
    val requestedBy: Long,
    /** Clave de idempotencia del móvil, para que un reintento no duplique. */
    val idempotencyKey: String?,
) {
    /** El movimiento del ledger que corresponde a este ajuste. */
    val movementType: MovementType get() = direction.movementType

    /**
     * Indica si este ajuste necesita que otra persona lo apruebe.
     *
     * **Solo los aumentos.** Una merma se detecta sola en el siguiente conteo; un
     * aumento sin justificar es la forma más sencilla de tapar un robo, así que
     * necesita una segunda firma. Que un faltante no exija aprobación es
     * deliberado: bloquear la corrección de un error evidente solo hace que la
     * gente deje de corregirlos.
     */
    val requiresApproval: Boolean get() = direction == AdjustmentDirection.INCREASE

    companion object {
        /** Longitud mínima del motivo escrito, cuando se exige. */
        const val MIN_NOTE_LENGTH = 10

        /**
         * Crea una solicitud validada.
         *
         * @return la solicitud, o el error de dominio que explica qué falta.
         */
        fun create(
            productCode: ProductCode,
            locationLabel: String,
            quantity: Quantity,
            direction: AdjustmentDirection,
            reason: AdjustmentReason,
            origin: AdjustmentOrigin,
            note: String?,
            requestedBy: Long,
            idempotencyKey: String? = null,
        ): DomainResult<AdjustmentRequest> {
            // ── La cantidad tiene que ser POSITIVA, no solo distinta de cero ──
            //
            // **Comprobar `isZero` NO basta, y era un agujero real.** Con una cantidad
            // NEGATIVA y dirección DECREASE, el movimiento resta un número negativo,
            // o sea **SUMA inventario**, y además `requiresApproval` sería `false`
            // porque mira la DIRECCIÓN. Resultado: se puede aumentar el stock sin
            // aprobación de nadie, que es exactamente lo que la decisión R-74
            // pretende impedir.
            //
            // Lo encontró un agente leyendo esta clase: `Quantity.ofScaled` no valida
            // el signo, así que el valor negativo llegaba hasta aquí. Se valida en el
            // TIPO y no en el endpoint: si dependiera de que cada ruta se acuerde,
            // la próxima ruta que se añada tendría el mismo agujero.
            if (quantity.isZero || quantity.isNegative) {
                return DomainError.InvalidQuantity(
                    "La cantidad de un ajuste debe ser mayor que cero. " +
                        "La dirección ya indica si suma o resta: una cantidad negativa " +
                        "invertiría el movimiento y aumentaría el inventario sin " +
                        "necesitar aprobación.",
                ).asLeft()
            }

            // ── Motivo escrito cuando el motivo lo exige ────────────────────
            if (reason.requiresNote) {
                val text = note?.trim().orEmpty()
                if (text.length < MIN_NOTE_LENGTH) {
                    return DomainError.ReasonRequired(
                        "El motivo «${reason.displayName}» exige una explicación de al " +
                            "menos $MIN_NOTE_LENGTH caracteres. Sin ella, el ajuste no " +
                            "se puede auditar dentro de un año.",
                    ).asLeft()
                }
            }

            // ── Quien pide tiene que estar identificado ─────────────────────
            // Sin esto no se puede comprobar la segregación de funciones ni
            // auditar quién movió el inventario.
            if (requestedBy <= 0) {
                return DomainError.PermissionDenied(
                    "Un ajuste de inventario exige un usuario identificado. " +
                        "Sin él no hay segregación de funciones ni auditoría.",
                ).asLeft()
            }

            return AdjustmentRequest(
                productCode = productCode,
                locationLabel = locationLabel,
                quantity = quantity,
                direction = direction,
                reason = reason,
                origin = origin,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                requestedBy = requestedBy,
                idempotencyKey = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() },
            ).asRight()
        }
    }
}

/**
 * Decide si un ajuste necesita aprobación y quién puede darla.
 *
 * **Función pura, sin base de datos.** Es la parte de la regla que se puede probar
 * exhaustivamente en milisegundos, y la que decide si el movimiento se escribe ya
 * o queda pendiente.
 */
object AdjustmentPolicy {

    /**
     * Qué hacer con la solicitud.
     *
     * Los tres casos son distintos y el llamador tiene que poder distinguirlos:
     * escribir el movimiento, dejarlo pendiente, o rechazarlo.
     */
    sealed interface Decision {
        /** Se puede escribir el movimiento ahora. */
        data object Apply : Decision

        /**
         * Queda pendiente de aprobación.
         *
         * **No se escribe nada en el ledger todavía.** Un movimiento que espera
         * aprobación y ya movió el inventario sería un cambio de stock sin
         * autorización, que es justo lo que la aprobación pretende evitar.
         */
        data class PendingApproval(val reason: String) : Decision

        /** Se rechaza. */
        data class Reject(val error: DomainError) : Decision
    }

    /**
     * Decide qué hacer.
     *
     * @param request la solicitud, ya validada.
     * @param approverId quién aprobaría, si hiciera falta. `null` si nadie.
     * @param available el saldo disponible en la ubicación. `null` si no se sabe.
     */
    fun decide(
        request: AdjustmentRequest,
        approverId: Long? = null,
        available: Quantity? = null,
    ): Decision {

        // ── Un descenso no puede dejar el saldo en negativo ─────────────────
        // Se comprueba aquí para dar un mensaje útil, aunque la base también lo
        // impide con un CHECK. El CHECK dice «stock_non_negative»; este mensaje
        // dice cuánto hay y cuánto se pretende sacar.
        if (request.direction == AdjustmentDirection.DECREASE && available != null) {
            if (request.quantity > available) {
                return Decision.Reject(
                    // El error lleva los NÚMEROS y la ubicación, no solo un
                    // mensaje: un «stock insuficiente» sin cantidades obliga a
                    // mirar la base para saber qué pasó.
                    DomainError.InsufficientStock(
                        available = available.units,
                        requested = request.quantity.units,
                        location = request.locationLabel,
                    ),
                )
            }
        }

        // ── Sin aprobación requerida: se aplica ────────────────────────────
        if (!request.requiresApproval) return Decision.Apply

        // ── Requiere aprobación y no hay aprobador ──────────────────────────
        if (approverId == null) {
            return Decision.PendingApproval(
                "Un ajuste que AUMENTA el inventario necesita la aprobación de otra " +
                    "persona. Una merma se detecta sola en el siguiente conteo; un " +
                    "aumento sin justificar es la forma más sencilla de tapar un robo.",
            )
        }

        // ── El aprobador no puede ser quien lo pide ─────────────────────────
        // Decisión R-74. Es el control que impide que alguien cubra su propio
        // error sin que nadie lo revise.
        if (approverId == request.requestedBy) {
            return Decision.Reject(
                DomainError.SegregationOfDuties(
                    "Quien solicita un ajuste no puede aprobarlo. El usuario " +
                        "${request.requestedBy} tendría que aprobar su propia solicitud.",
                ),
            )
        }

        return Decision.Apply
    }
}
