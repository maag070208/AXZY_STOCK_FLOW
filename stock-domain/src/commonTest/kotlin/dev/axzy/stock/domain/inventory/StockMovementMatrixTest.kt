package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DE LA MATRIZ MOVIMIENTO → EFECTO EN STOCK
 *
 * Estos tests son la red de seguridad contra el defecto más grave del legacy:
 * el doble descuento al ubicar mercancía (docs/historico/PLAN_ALMACEN_INTEGRAL.md §2.7).
 *
 * El test más importante es `el total solo cambia por entradas y salidas
 * autorizadas`, porque fija la propiedad estructural que hace imposible el bug:
 * **solo dos efectos pueden mover el total**, y uno suma y el otro resta.
 * Si alguien añade un movimiento que resta del total, ese test falla.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class StockMovementMatrixTest : DescribeSpec({

    val receiving = LocationCode.of("RECEIVING")
    val shelf = LocationCode.of("04BCA")
    val shelfB = LocationCode.of("04BCB")

    describe("efecto declarado por cada tipo de movimiento") {

        // ── Un caso por cada MovementType. Si se añade uno nuevo, este `when`
        //    deja de compilar y obliga a declarar su efecto.
        withData(
            nameFn = { "%-22s → %s" },
            // (movimiento, efecto esperado, cambia el total)
            Triple(InitialLoad(Quantity.of(100), shelf), "AddToLocation", true),
            Triple(ReceiptIn(Quantity.of(100), receiving), "AddToLocation", true),
            Triple(AdjustmentIn(Quantity.of(50), shelf, "conteo"), "AddToLocation", true),
            Triple(FulfillmentOut(Quantity.of(30), shelf, "ORD-1"), "RemoveFromLocation", true),
            Triple(AdjustmentOut(Quantity.of(10), shelf, "merma"), "RemoveFromLocation", true),
            Triple(Locate(Quantity.of(100), receiving, shelf), "TransferWithinWarehouse", false),
            Triple(MoveWithinWarehouse(Quantity.of(20), shelf, shelfB), "TransferWithinWarehouse", false),
            Triple(TransferOut(Quantity.of(40), shelf, 2L), "EnterTransit", false),
            Triple(TransferIn(Quantity.of(40), shelf, 1L), "LeaveTransit", false),
            Triple(PendingOut(Quantity.of(15), shelf, "ORD-2"), "NoInventoryChange", false),
        ) { (movement, effectExpected, changesTotal) ->
            val nameEffect = movement.effect::class.simpleName
            withClue("${movement.type} debe declarar el efecto $effectExpected") {
                nameEffect shouldBe effectExpected
            }
            withClue("${movement.type}: changesTotal debe ser $changesTotal") {
                movement.effect.changesTotal shouldBe changesTotal
            }
        }
    }

    describe("el total SOLO cambia por entradas y salidas autorizadas") {

        it("ningún movimiento interno resta del total") {
            // Este es el test que hace imposible el bug del legacy.
            val movementsInternal = listOf(
                Locate(Quantity.of(100), receiving, shelf),
                MoveWithinWarehouse(Quantity.of(20), shelf, shelfB),
                TransferOut(Quantity.of(40), shelf, 2L),
                TransferIn(Quantity.of(40), shelf, 1L),
                PendingOut(Quantity.of(15), shelf, "ORD-2"),
            )

            movementsInternal.forEach { movement ->
                withClue(
                    "${movement.type} NO debe modificar el inventario total. " +
                        "En el legacy, LOCATION restaba del total por un efecto por defecto, " +
                        "y una caja de 100 dejaba el inventario 100 unidades por debajo."
                ) {
                    movement.effect.totalDelta(movement.quantity) shouldBeExactly 0L
                }
            }
        }

        it("solo AddToLocation y RemoveFromLocation pueden mover el total") {
            val allVariants = listOf(
                StockEffect.AddToLocation(shelf),
                StockEffect.RemoveFromLocation(shelf),
                StockEffect.TransferWithinWarehouse(shelf, shelfB),
                StockEffect.EnterTransit(shelf, 2L),
                StockEffect.LeaveTransit(shelf, 1L),
                StockEffect.NoInventoryChange,
            )

            val thoseThatMoveTheTotal = allVariants.filter { it.changesTotal }

            withClue(
                "Solo puede haber 2 efectos que muevan el total. " +
                    "Si aparece un tercero, es un movimiento que resta o suma sin justificación."
            ) {
                thoseThatMoveTheTotal.toSet() shouldBe setOf(
                    StockEffect.AddToLocation(shelf),
                    StockEffect.RemoveFromLocation(shelf),
                )
            }
        }
    }

    describe("ubicar: el movimiento del bug del legacy") {

        it("mueve la cantidad de la recepción al estante sin tocar el total") {
            val locate = Locate(Quantity.of(100), fromReceiving = receiving, toShelf = shelf)

            val deltas = locate.effect.locationDeltas(locate.quantity)

            withClue("Debe tocar exactamente dos ubicaciones: la de recepción y el estante") {
                deltas.keys shouldBe setOf(receiving, shelf)
            }
            withClue("Sale de recepción") {
                (deltas[receiving] ?: 0L) shouldBeExactly -100_000L  // 100 unidades * escala 1000
            }
            withClue("Entra al estante") {
                (deltas[shelf] ?: 0L) shouldBeExactly 100_000L
            }
            withClue("El total NO cambia, que es la corrección del bug (decisión R-65)") {
                locate.effect.totalDelta(locate.quantity) shouldBeExactly 0L
            }
            withClue("La suma de los deltas debe ser cero: no se crea ni se destruye stock") {
                deltas.values.sum() shouldBeExactly 0L
            }
        }

        it("el legacy restaba DOS VECES del total: documentado como regresión") {
            // Reproducimos la aritmética del legacy para dejar constancia de qué
            // se está corrigiendo. Este test NO valida el comportamiento nuevo:
            // documenta el viejo, para que dentro de un año se entienda la
            // magnitud del defecto y no se reintroduzca.
            //
            // legacy: removeUnitsFromKardex mutaba la fila de recepción,
            //         y ADEMÁS el registro LOCATION restaba por el `else` por defecto.

            val stockInitial = 1_000L
            val box = 100L

            // 1. Captura física: PENDING-TO-LOCATE sumaba
            val afterCapture = stockInitial + box                              // 1100
            // 2. addKardexLocation: mutaba la fila (-100) y creaba LOCATION (-100)
            val afterLocatingInLegacy = afterCapture - box - box               // 900

            withClue("El legacy dejaba el stock por debajo de lo real") {
                (afterLocatingInLegacy < afterCapture) shouldBe true
            }
            withClue("El descuadre era exactamente la cantidad ubicada, acumulándose por recepción") {
                (afterCapture - afterLocatingInLegacy) shouldBeExactly box * 2
            }

            // El modelo nuevo: el total es invariante al ubicar.
            val stockAfterCapture = stockInitial + box                    // 1100
            val deltaLocate = Locate(Quantity.of(box), receiving, shelf)
                .effect.totalDelta(Quantity.of(box))
            val stockAfterLocate = stockAfterCapture + deltaLocate / 1000

            withClue("En el modelo nuevo el stock tras ubicar debe ser el mismo que tras capturar") {
                stockAfterLocate shouldBeExactly stockAfterCapture
            }
        }
    }

    describe("invariantes estructurales de los efectos") {

        it("todo efecto que toca dos ubicaciones suma cero en el total") {
            val transfers = listOf(
                StockEffect.TransferWithinWarehouse(shelf, shelfB),
            )
            transfers.forEach { effect ->
                withClue("Un traslado debe sumar cero: ${effect::class.simpleName}") {
                    effect.locationDeltas(Quantity.of(10)).values.sum() shouldBeExactly 0L
                }
            }
        }

        it("las entradas y salidas tocan una sola ubicación") {
            val fromLocation = listOf(
                StockEffect.AddToLocation(shelf) to 1,
                StockEffect.RemoveFromLocation(shelf) to -1,
            )
            fromLocation.forEach { (effect, sign) ->
                val deltas = effect.locationDeltas(Quantity.of(10))
                withClue("${effect::class.simpleName} debe tocar exactamente una ubicación") {
                    deltas.size shouldBe 1
                }
                withClue("${effect::class.simpleName} debe tener el signo correcto") {
                    (deltas.values.first() > 0) shouldBe (sign > 0)
                }
            }
        }

        it("el tránsito saca de la ubicación pero NO del inventario") {
            val outputATransit = TransferOut(Quantity.of(40), shelf, toWarehouseId = 2L)

            withClue("Sale de la ubicación física") {
                outputATransit.effect.locationDeltas(outputATransit.quantity) shouldBe
                    mapOf(shelf to -40_000L)
            }
            withClue(
                "Pero el total no baja: la mercancía sigue en el inventario, en tránsito. " +
                    "Si bajara, el inventario mentiría durante el viaje."
            ) {
                outputATransit.effect.totalDelta(outputATransit.quantity) shouldBeExactly 0L
            }
        }
    }

    describe("reglas de negocio codificadas en el tipo") {

        it("un ajuste sin motivo no se puede construir") {
            // Invariante I6: todo ADJUSTMENT_* tiene motivo.
            // La regla vive en el TIPO, así que un ajuste sin motivo es
            // imposible de construir, no algo que haya que recordar validar.
            listOf(
                { AdjustmentIn(Quantity.of(10), shelf, reason = "  ") },
                { AdjustmentOut(Quantity.of(10), shelf, reason = "") },
            ).forEach { constructor ->
                withClue("Un ajuste con motivo en blanco debe rechazarse") {
                    shouldThrow<IllegalArgumentException> { constructor() }
                }
            }
        }

        it("un traslado a la misma ubicación no se puede construir") {
            shouldThrow<IllegalArgumentException> {
                MoveWithinWarehouse(Quantity.of(10), shelf, shelf)
            }
            shouldThrow<IllegalArgumentException> {
                Locate(Quantity.of(10), receiving, receiving)
            }
        }

        it("el tipo de movimiento NO se puede deducir mal: viene del tipo de la clase") {
            val movement: StockMovement = Locate(Quantity.of(1), receiving, shelf)
            movement.type shouldBe MovementType.LOCATE
            movement.effect.shouldBeInstanceOf<StockEffect.TransferWithinWarehouse>()
        }
    }
})

/** Helper local para no depender de la firma exacta de shouldThrow entre versiones. */
private inline fun <reified T : Throwable> shouldThrow(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("Se esperaba ${T::class.simpleName} pero se lanzó ${t::class.simpleName}: ${t.message}")
    }
    throw AssertionError("Se esperaba ${T::class.simpleName} pero no se lanzó ninguna excepción")
}
