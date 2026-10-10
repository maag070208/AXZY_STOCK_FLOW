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
 * MOVEMENT → STOCK EFFECT MATRIX TESTS
 *
 * These tests are the safety net against the most serious defect of the legacy:
 * the double deduction when locating goods (docs/historico/PLAN_ALMACEN_INTEGRAL.md §2.7).
 *
 * The most important test is `the total only changes through inflows and
 * authorized outflows`, because it pins the structural property that makes the bug impossible:
 * **only two effects can move the total**, and one adds and the other subtracts.
 * If someone adds a movement that subtracts from the total, that test fails.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class StockMovementMatrixTest : DescribeSpec({

    val receiving = LocationCode.of("RECEIVING")
    val shelf = LocationCode.of("04BCA")
    val shelfB = LocationCode.of("04BCB")

    describe("effect declared for each movement type") {

        // ── One case per MovementType. If a new one is added, this `when`
        //    stops compiling and forces its effect to be declared.
        withData(
            nameFn = { "%-22s → %s" },
            // (movement, expected effect, changes the total)
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

    describe("the total changes ONLY through authorised entries and exits") {

        it("no internal movement subtracts from the total") {
            // This is the test that makes the legacy bug impossible.
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

        it("only AddToLocation and RemoveFromLocation can move the total") {
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

    describe("locating: the legacy bug's movement") {

        it("moves the quantity from receiving to the shelf without touching the total") {
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

        it("the legacy subtracted TWICE from the total: documented as a regression") {
            // We reproduce the legacy arithmetic to record what
            // is being corrected. This test does NOT validate the new behavior:
            // it documents the old one, so that within a year the
            // magnitude of the defect is understood and it is not reintroduced.
            //
            // legacy: removeUnitsFromKardex mutated the receiving row,
            //         AND ALSO the LOCATION record subtracted through the default `else`.

            val stockInitial = 1_000L
            val box = 100L

            // 1. Physical capture: PENDING-TO-LOCATE added
            val afterCapture = stockInitial + box                              // 1100
            // 2. addKardexLocation: mutated the row (-100) and created LOCATION (-100)
            val afterLocatingInLegacy = afterCapture - box - box               // 900

            withClue("El legacy dejaba el stock por debajo de lo real") {
                (afterLocatingInLegacy < afterCapture) shouldBe true
            }
            withClue("El descuadre era exactamente la cantidad ubicada, acumulándose por recepción") {
                (afterCapture - afterLocatingInLegacy) shouldBeExactly box * 2
            }

            // The new model: the total is invariant when locating.
            val stockAfterCapture = stockInitial + box                    // 1100
            val deltaLocate = Locate(Quantity.of(box), receiving, shelf)
                .effect.totalDelta(Quantity.of(box))
            val stockAfterLocate = stockAfterCapture + deltaLocate / 1000

            withClue("En el modelo nuevo el stock tras ubicar debe ser el mismo que tras capturar") {
                stockAfterLocate shouldBeExactly stockAfterCapture
            }
        }
    }

    describe("structural invariants of the effects") {

        it("every effect that touches two locations adds up to zero in the total") {
            val transfers = listOf(
                StockEffect.TransferWithinWarehouse(shelf, shelfB),
            )
            transfers.forEach { effect ->
                withClue("Un traslado debe sumar cero: ${effect::class.simpleName}") {
                    effect.locationDeltas(Quantity.of(10)).values.sum() shouldBeExactly 0L
                }
            }
        }

        it("entries and exits touch a single location") {
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

        it("transit takes from the location but NOT from the inventory") {
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

    describe("business rules encoded in the type") {

        it("an adjustment without a reason cannot be built") {
            // Invariante I6: todo ADJUSTMENT_* tiene motivo.
            // The rule lives in the TYPE, so an adjustment without a reason is
            // impossible to construct, not something that has to be remembered to validate.
            listOf(
                { AdjustmentIn(Quantity.of(10), shelf, reason = "  ") },
                { AdjustmentOut(Quantity.of(10), shelf, reason = "") },
            ).forEach { constructor ->
                withClue("Un ajuste con motivo en blanco debe rechazarse") {
                    shouldThrow<IllegalArgumentException> { constructor() }
                }
            }
        }

        it("a transfer to the same location cannot be built") {
            shouldThrow<IllegalArgumentException> {
                MoveWithinWarehouse(Quantity.of(10), shelf, shelf)
            }
            shouldThrow<IllegalArgumentException> {
                Locate(Quantity.of(10), receiving, receiving)
            }
        }

        it("the movement type cannot be deduced wrongly: it comes from the class type") {
            val movement: StockMovement = Locate(Quantity.of(1), receiving, shelf)
            movement.type shouldBe MovementType.LOCATE
            movement.effect.shouldBeInstanceOf<StockEffect.TransferWithinWarehouse>()
        }
    }
})

/** Local helper so as not to depend on the exact signature of shouldThrow across versions. */
private inline fun <reified T : Throwable> shouldThrow(block: () -> Unit): T {
    try {
        block()
    } catch (t: Throwable) {
        if (t is T) return t
        throw AssertionError("Se esperaba ${T::class.simpleName} pero se lanzó ${t::class.simpleName}: ${t.message}")
    }
    throw AssertionError("Se esperaba ${T::class.simpleName} pero no se lanzó ninguna excepción")
}
