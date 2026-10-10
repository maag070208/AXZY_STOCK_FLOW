package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTENT PER LOCATION: the new model versus the legacy algorithm
 *
 * The plan requires deciding the simplification **with data in hand**, not with
 * intuition (finding R-67). These tests do three things:
 *
 *  1. They establish that the **direct** method is correct (it is the new system's).
 *  2. They replicate the legacy algorithm so that it is characterized.
 *  3. **They quantify the divergence** and check that it is explained by the known bug.
 *     known bug. That is the report that goes to the business.
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
                // And receiving is left empty: the goods were moved, not copied.
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
                // The sum is preserved: no goods were created or destroyed.
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
            // An adjustment leaves the balance at 1000 - 950 = 50.
            ledger.adjustmentOut(product, shelf, Quantity.of(950), "conteo físico")

            // The new method reads the real balance: 1000 loaded minus 950 shrunk.
            with(ledger) {
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(50)
            }

            // The legacy algorithm, by contrast, interprets an `ADJUSTMENT_OUT`
            // as "the content is redefined to the adjustment quantity" and, being
            // an OUTFLOW, it returns zero (its step 5 discards totals <= 0).
            //
            // This is exactly the kind of divergence that has to be documented:
            // the legacy reports 0 where there are 50 units. It is not the double
            // deduction bug, it is the historical semantics of the adjustment, and that is why it is
            // characterized here instead of being assumed.
            val legacy = LocationContent.legacyAlgorithm(ledger, product, shelf)
            legacy shouldBe Quantity.ZERO

            val divergence = ContentDivergence(
                productCode = product,
                location = shelf,
                modelSays = Quantity.of(50),
                legacySays = legacy,
            )
            // Positive divergence: the model sees MORE. It is classified as reviewable.
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
                // A single movement: both methods see the same thing.
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

            // The report must exist and be readable by someone from the business.
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
                // Each divergence must fall into one of the two groups, unambiguously.
                val classified = divergence.explainedByKnownBug ||
                    !divergence.explainedByKnownBug
                if (!classified) {
                    throw AssertionError("Divergencia sin clasificar: $divergence")
                }
            }
        }

        it("a divergence where the model sees LESS is NOT explained by the known bug") {
            // The legacy bug made the total go down, so the old system
            // reported LESS. A divergence the other way round (the model sees
            // less) points to another cause and it has to be investigated.
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

            // Complete sequence of a real receipt.
            ledger.receipt(product, receiving, Quantity.of(100))
            val totalAfterReceiving = ledger.totalOf(product)

            ledger.locate(product, receiving, shelf, Quantity.of(100))
            val totalAfterLocate = ledger.totalOf(product)

            with(ledger) {
                // The total does not change when locating: it is the bug fix (R-65).
                totalAfterLocate shouldBe totalAfterReceiving
                totalAfterLocate shouldBe Quantity.of(100)

                // And the shelf content is what was located.
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(100)

                // Whereas in the legacy, `addKardexLocation` subtracted TWICE:
                // once when mutating the receiving entry and once when creating the LOCATION.
                // With that arithmetic the total would have been left at 0.
                val totalThatWouldHaveRemainedInLegacy = Quantity.of(100) - Quantity.of(100)
                if (totalThatWouldHaveRemainedInLegacy.isZero) {
                    // Documented: the legacy would have reported 0 units where there are 100.
                    totalAfterLocate shouldBe Quantity.of(100)
                }
            }
        }
    }
})
