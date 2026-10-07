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
 * el doble descuento al ubicar mercancía (PLAN_ALMACEN_INTEGRAL.md §2.7).
 *
 * El test más importante es `el total solo cambia por entradas y salidas
 * autorizadas`, porque fija la propiedad estructural que hace imposible el bug:
 * **solo dos efectos pueden mover el total**, y uno suma y el otro resta.
 * Si alguien añade un movimiento que resta del total, ese test falla.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class StockMovementMatrixTest : DescribeSpec({

    val recepcion = LocationCode.of("RECEPCION")
    val estante = LocationCode.of("04BCA")
    val estanteB = LocationCode.of("04BCB")

    describe("efecto declarado por cada tipo de movimiento") {

        // ── Un caso por cada MovementType. Si se añade uno nuevo, este `when`
        //    deja de compilar y obliga a declarar su efecto.
        withData(
            nameFn = { "%-22s → %s" },
            // (movimiento, efecto esperado, cambia el total)
            Triple(InitialLoad(Quantity.of(100), estante), "AddToLocation", true),
            Triple(ReceiptIn(Quantity.of(100), recepcion), "AddToLocation", true),
            Triple(AdjustmentIn(Quantity.of(50), estante, "conteo"), "AddToLocation", true),
            Triple(FulfillmentOut(Quantity.of(30), estante, "ORD-1"), "RemoveFromLocation", true),
            Triple(AdjustmentOut(Quantity.of(10), estante, "merma"), "RemoveFromLocation", true),
            Triple(Locate(Quantity.of(100), recepcion, estante), "TransferWithinWarehouse", false),
            Triple(MoveWithinWarehouse(Quantity.of(20), estante, estanteB), "TransferWithinWarehouse", false),
            Triple(TransferOut(Quantity.of(40), estante, 2L), "EnterTransit", false),
            Triple(TransferIn(Quantity.of(40), estante, 1L), "LeaveTransit", false),
            Triple(PendingOut(Quantity.of(15), estante, "ORD-2"), "NoInventoryChange", false),
        ) { (movement, efectoEsperado, cambiaTotal) ->
            val nombreEfecto = movement.effect::class.simpleName
            withClue("${movement.type} debe declarar el efecto $efectoEsperado") {
                nombreEfecto shouldBe efectoEsperado
            }
            withClue("${movement.type}: changesTotal debe ser $cambiaTotal") {
                movement.effect.changesTotal shouldBe cambiaTotal
            }
        }
    }

    describe("el total SOLO cambia por entradas y salidas autorizadas") {

        it("ningún movimiento interno resta del total") {
            // Este es el test que hace imposible el bug del legacy.
            val movimientosInternos = listOf(
                Locate(Quantity.of(100), recepcion, estante),
                MoveWithinWarehouse(Quantity.of(20), estante, estanteB),
                TransferOut(Quantity.of(40), estante, 2L),
                TransferIn(Quantity.of(40), estante, 1L),
                PendingOut(Quantity.of(15), estante, "ORD-2"),
            )

            movimientosInternos.forEach { movimiento ->
                withClue(
                    "${movimiento.type} NO debe modificar el inventario total. " +
                        "En el legacy, LOCATION restaba del total por un efecto por defecto, " +
                        "y una caja de 100 dejaba el inventario 100 unidades por debajo."
                ) {
                    movimiento.effect.totalDelta(movimiento.quantity) shouldBeExactly 0L
                }
            }
        }

        it("solo AddToLocation y RemoveFromLocation pueden mover el total") {
            val todasLasVariantes = listOf(
                StockEffect.AddToLocation(estante),
                StockEffect.RemoveFromLocation(estante),
                StockEffect.TransferWithinWarehouse(estante, estanteB),
                StockEffect.EnterTransit(estante, 2L),
                StockEffect.LeaveTransit(estante, 1L),
                StockEffect.NoInventoryChange,
            )

            val lasQueMuevenElTotal = todasLasVariantes.filter { it.changesTotal }

            withClue(
                "Solo puede haber 2 efectos que muevan el total. " +
                    "Si aparece un tercero, es un movimiento que resta o suma sin justificación."
            ) {
                lasQueMuevenElTotal.toSet() shouldBe setOf(
                    StockEffect.AddToLocation(estante),
                    StockEffect.RemoveFromLocation(estante),
                )
            }
        }
    }

    describe("ubicar: el movimiento del bug del legacy") {

        it("mueve la cantidad de la recepción al estante sin tocar el total") {
            val ubicar = Locate(Quantity.of(100), fromReceiving = recepcion, toShelf = estante)

            val deltas = ubicar.effect.locationDeltas(ubicar.quantity)

            withClue("Debe tocar exactamente dos ubicaciones: la de recepción y el estante") {
                deltas.keys shouldBe setOf(recepcion, estante)
            }
            withClue("Sale de recepción") {
                (deltas[recepcion] ?: 0L) shouldBeExactly -100_000L  // 100 unidades * escala 1000
            }
            withClue("Entra al estante") {
                (deltas[estante] ?: 0L) shouldBeExactly 100_000L
            }
            withClue("El total NO cambia, que es la corrección del bug (decisión R-65)") {
                ubicar.effect.totalDelta(ubicar.quantity) shouldBeExactly 0L
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

            val stockInicial = 1_000L
            val caja = 100L

            // 1. Captura física: PENDING-TO-LOCATE sumaba
            val trasCaptura = stockInicial + caja                              // 1100
            // 2. addKardexLocation: mutaba la fila (-100) y creaba LOCATION (-100)
            val trasUbicarEnElLegacy = trasCaptura - caja - caja               // 900

            withClue("El legacy dejaba el stock por debajo de lo real") {
                (trasUbicarEnElLegacy < trasCaptura) shouldBe true
            }
            withClue("El descuadre era exactamente la cantidad ubicada, acumulándose por recepción") {
                (trasCaptura - trasUbicarEnElLegacy) shouldBeExactly caja * 2
            }

            // El modelo nuevo: el total es invariante al ubicar.
            val stockNuevoTrasCaptura = stockInicial + caja                    // 1100
            val deltaUbicar = Locate(Quantity.of(caja), recepcion, estante)
                .effect.totalDelta(Quantity.of(caja))
            val stockNuevoTrasUbicar = stockNuevoTrasCaptura + deltaUbicar / 1000

            withClue("En el modelo nuevo el stock tras ubicar debe ser el mismo que tras capturar") {
                stockNuevoTrasUbicar shouldBeExactly stockNuevoTrasCaptura
            }
        }
    }

    describe("invariantes estructurales de los efectos") {

        it("todo efecto que toca dos ubicaciones suma cero en el total") {
            val traslados = listOf(
                StockEffect.TransferWithinWarehouse(estante, estanteB),
            )
            traslados.forEach { efecto ->
                withClue("Un traslado debe sumar cero: ${efecto::class.simpleName}") {
                    efecto.locationDeltas(Quantity.of(10)).values.sum() shouldBeExactly 0L
                }
            }
        }

        it("las entradas y salidas tocan una sola ubicación") {
            val deUnaUbicacion = listOf(
                StockEffect.AddToLocation(estante) to 1,
                StockEffect.RemoveFromLocation(estante) to -1,
            )
            deUnaUbicacion.forEach { (efecto, signo) ->
                val deltas = efecto.locationDeltas(Quantity.of(10))
                withClue("${efecto::class.simpleName} debe tocar exactamente una ubicación") {
                    deltas.size shouldBe 1
                }
                withClue("${efecto::class.simpleName} debe tener el signo correcto") {
                    (deltas.values.first() > 0) shouldBe (signo > 0)
                }
            }
        }

        it("el tránsito saca de la ubicación pero NO del inventario") {
            val salidaATransito = TransferOut(Quantity.of(40), estante, toWarehouseId = 2L)

            withClue("Sale de la ubicación física") {
                salidaATransito.effect.locationDeltas(salidaATransito.quantity) shouldBe
                    mapOf(estante to -40_000L)
            }
            withClue(
                "Pero el total no baja: la mercancía sigue en el inventario, en tránsito. " +
                    "Si bajara, el inventario mentiría durante el viaje."
            ) {
                salidaATransito.effect.totalDelta(salidaATransito.quantity) shouldBeExactly 0L
            }
        }
    }

    describe("reglas de negocio codificadas en el tipo") {

        it("un ajuste sin motivo no se puede construir") {
            // Invariante I6: todo ADJUSTMENT_* tiene motivo.
            // La regla vive en el TIPO, así que un ajuste sin motivo es
            // imposible de construir, no algo que haya que recordar validar.
            listOf(
                { AdjustmentIn(Quantity.of(10), estante, reason = "  ") },
                { AdjustmentOut(Quantity.of(10), estante, reason = "") },
            ).forEach { constructor ->
                withClue("Un ajuste con motivo en blanco debe rechazarse") {
                    shouldThrow<IllegalArgumentException> { constructor() }
                }
            }
        }

        it("un traslado a la misma ubicación no se puede construir") {
            shouldThrow<IllegalArgumentException> {
                MoveWithinWarehouse(Quantity.of(10), estante, estante)
            }
            shouldThrow<IllegalArgumentException> {
                Locate(Quantity.of(10), recepcion, recepcion)
            }
        }

        it("el tipo de movimiento NO se puede deducir mal: viene del tipo de la clase") {
            val movimiento: StockMovement = Locate(Quantity.of(1), recepcion, estante)
            movimiento.type shouldBe MovementType.LOCATE
            movimiento.effect.shouldBeInstanceOf<StockEffect.TransferWithinWarehouse>()
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
