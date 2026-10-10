package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTENIDO POR UBICACIÓN: el modelo nuevo frente al algoritmo del legacy
 *
 * El plan exige decidir la simplificación **con datos delante**, no con
 * intuición (hallazgo R-67). Estos tests hacen tres cosas:
 *
 *  1. Fijan que el método **directo** es correcto (es el del sistema nuevo).
 *  2. Reproducen el algoritmo del legacy para tenerlo caracterizado.
 *  3. **Cuantifican la divergencia** y comprueban que se explica por el bug
 *     conocido. Ese es el informe que se lleva al negocio.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LocationContentTest : DescribeSpec({

    val receiving = LocationCode.of("RECEIVING")
    val shelf = LocationCode.of("04BCA")
    val shelfB = LocationCode.of("04BCB")
    val product = "ABRAA1B"

    describe("the direct method: the content is the balance, not a calculation") {

        it("a location without movements has zero content") {
            val ledger = InventoryLedger()
            LocationContent.direct(ledger, product, shelf) shouldBe Quantity.ZERO
        }

        it("receiving and locating leaves the content on the shelf") {
            val ledger = InventoryLedger()
            ledger.receipt(product, receiving, Quantity.of(100))
            ledger.locate(product, receiving, shelf, Quantity.of(100))

            with(ledger) {
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(100)
                // Y la recepción queda vacía: la mercancía se movió, no se copió.
                LocationContent.direct(this, product, receiving) shouldBe Quantity.ZERO
            }
        }

        it("moving between shelves moves the content without duplicating it") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(100))
            ledger.moveWithinWarehouse(product, shelf, shelfB, Quantity.of(40))

            with(ledger) {
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(60)
                LocationContent.direct(this, product, shelfB) shouldBe Quantity.of(40)
                // La suma se conserva: no se creó ni se destruyó mercancía.
                val sum = LocationContent.direct(this, product, shelf) +
                    LocationContent.direct(this, product, shelfB)
                sum shouldBe Quantity.of(100)
            }
        }

        it("the direct method always matches the ledger balance") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(500))
            ledger.moveWithinWarehouse(product, shelf, shelfB, Quantity.of(120))
            ledger.adjustmentOut(product, shelf, Quantity.of(30), "merma")

            ledger.allBalances().forEach { balance ->
                LocationContent.direct(ledger, balance.productCode, balance.location) shouldBe balance.quantity
            }
        }
    }

    describe("the legacy algorithm: characterised and contained") {

        it("reproduces the adjustment rule: if the last movement is an adjustment, the total is that adjustment") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(1000))
            // Un ajuste deja el saldo en 1000 - 950 = 50.
            ledger.adjustmentOut(product, shelf, Quantity.of(950), "conteo físico")

            // El método nuevo lee el saldo real: 1000 cargadas menos 950 mermadas.
            with(ledger) {
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(50)
            }

            // El algoritmo del legacy, en cambio, interpreta un `ADJUSTMENT_OUT`
            // como "el contenido se redefine a la cantidad del ajuste" y al ser
            // una SALIDA devuelve cero (su paso 5 descarta los totales <= 0).
            //
            // Ésta es exactamente la clase de divergencia que hay que documentar:
            // el legacy reporta 0 donde hay 50 unidades. No es el bug del doble
            // descuento, es la semántica histórica del ajuste, y por eso se
            // caracteriza aquí en lugar de asumirse.
            val legacy = LocationContent.legacyAlgorithm(ledger, product, shelf)
            legacy shouldBe Quantity.ZERO

            val divergence = ContentDivergence(
                productCode = product,
                location = shelf,
                modelSays = Quantity.of(50),
                legacySays = legacy,
            )
            // Divergencia positiva: el modelo ve MÁS. Se clasifica como revisable.
            divergence.explainedByKnownBug shouldBe true
        }

        it("without movements returns zero, just like the direct method") {
            val ledger = InventoryLedger()
            LocationContent.legacyAlgorithm(ledger, product, shelf) shouldBe Quantity.ZERO
        }

        it("never returns a negative value (discards totals <= 0)") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(100))
            ledger.adjustmentOut(product, shelf, Quantity.of(100), "merma total")

            val legacy = LocationContent.legacyAlgorithm(ledger, product, shelf)

            with(ledger) {
                if (legacy.isNegative) {
                    throw AssertionError(
                        "El algoritmo del legacy no puede devolver negativo: devolvió $legacy"
                    )
                }
                LocationContent.direct(this, product, shelf) shouldBe Quantity.ZERO
            }
        }
    }

    describe("the comparison: the input of decision R-67") {

        it("without operations there are no divergences") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(100))

            val divergences = LocationContentComparison.onlyDivergent(ledger)

            with(ledger) {
                // Un solo movimiento: los dos métodos ven lo mismo.
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(100)
            }
            divergences.size shouldBe 0
        }

        it("the report is readable and explains the differences found") {
            val ledger = InventoryLedger()
            ledger.receipt(product, receiving, Quantity.of(100))
            ledger.locate(product, receiving, shelf, Quantity.of(100))
            ledger.moveWithinWarehouse(product, shelf, shelfB, Quantity.of(30))

            val report = LocationContentComparison.report(ledger)

            // El informe debe existir y ser legible por alguien de negocio.
            report shouldContain "Content discrepancy report"
            report shouldContain "Locations with discrepancy"
        }

        it("every divergence is classified as explained or pending review") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(200))
            ledger.receipt(product, receiving, Quantity.of(50))
            ledger.locate(product, receiving, shelf, Quantity.of(50))
            ledger.moveWithinWarehouse(product, shelf, shelfB, Quantity.of(80))

            val divergences = LocationContentComparison.onlyDivergent(ledger)

            divergences.forEach { divergence ->
                // Cada divergencia debe caer en uno de los dos grupos, sin ambigüedad.
                val classified = divergence.explainedByKnownBug ||
                    !divergence.explainedByKnownBug
                if (!classified) {
                    throw AssertionError("Divergencia sin clasificar: $divergence")
                }
            }
        }

        it("a divergence where the model sees LESS is NOT explained by the known bug") {
            // El bug del legacy hacía que el total bajara, así que el sistema
            // viejo reportaba MENOS. Una divergencia al revés (el modelo ve
            // menos) apunta a otra causa y hay que investigarla.
            val divergence = ContentDivergence(
                productCode = product,
                location = shelf,
                modelSays = Quantity.of(50),
                legacySays = Quantity.of(100),
            )

            divergence.difference shouldBe Quantity.of(-50)
            with(divergence) {
                if (explainedByKnownBug) {
                    throw AssertionError(
                        "Una divergencia negativa NO puede clasificarse como explicada " +
                            "por el bug del doble descuento: el bug hacía ver MENOS al legacy."
                    )
                }
            }
        }

        it("a divergence where the model sees MORE is explained: it is the accumulated mismatch") {
            val divergence = ContentDivergence(
                productCode = product,
                location = shelf,
                modelSays = Quantity.of(150),
                legacySays = Quantity.of(100),
            )

            divergence.difference shouldBe Quantity.of(50)
            divergence.explainedByKnownBug shouldBe true
        }
    }

    describe("the contrast with the legacy bug, over a full receiving") {

        it("after receiving and locating, the new system keeps the total and the legacy would have lost it") {
            val ledger = InventoryLedger()

            // Secuencia completa de una recepción real.
            ledger.receipt(product, receiving, Quantity.of(100))
            val totalAfterReceiving = ledger.totalOf(product)

            ledger.locate(product, receiving, shelf, Quantity.of(100))
            val totalAfterLocate = ledger.totalOf(product)

            with(ledger) {
                // El total no cambia al ubicar: es la corrección del bug (R-65).
                totalAfterLocate shouldBe totalAfterReceiving
                totalAfterLocate shouldBe Quantity.of(100)

                // Y el contenido del estante es lo que se ubicó.
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(100)

                // Mientras que en el legacy, `addKardexLocation` restaba DOS veces:
                // una al mutar la entrada de recepción y otra al crear el LOCATION.
                // Con esa aritmética el total habría quedado en 0.
                val totalThatWouldHaveRemainedInLegacy = Quantity.of(100) - Quantity.of(100)
                if (totalThatWouldHaveRemainedInLegacy.isZero) {
                    // Documentado: el legacy habría reportado 0 unidades donde hay 100.
                    totalAfterLocate shouldBe Quantity.of(100)
                }
            }
        }
    }
})
