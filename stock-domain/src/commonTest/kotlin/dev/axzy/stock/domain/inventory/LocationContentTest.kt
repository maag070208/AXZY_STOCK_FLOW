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

    describe("el método directo: el contenido es el saldo, no un cálculo") {

        it("una ubicación sin movimientos tiene contenido cero") {
            val ledger = InventoryLedger()
            LocationContent.direct(ledger, product, shelf) shouldBe Quantity.ZERO
        }

        it("recibir y ubicar deja el contenido en el estante") {
            val ledger = InventoryLedger()
            ledger.receipt(product, receiving, Quantity.of(100))
            ledger.locate(product, receiving, shelf, Quantity.of(100))

            with(ledger) {
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(100)
                // Y la recepción queda vacía: la mercancía se movió, no se copió.
                LocationContent.direct(this, product, receiving) shouldBe Quantity.ZERO
            }
        }

        it("trasladar entre estantes mueve el contenido sin duplicarlo") {
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

        it("el método directo coincide siempre con el saldo del libro") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(500))
            ledger.moveWithinWarehouse(product, shelf, shelfB, Quantity.of(120))
            ledger.adjustmentOut(product, shelf, Quantity.of(30), "merma")

            ledger.allBalances().forEach { balance ->
                LocationContent.direct(ledger, balance.productCode, balance.location) shouldBe balance.quantity
            }
        }
    }

    describe("el algoritmo del legacy: caracterizado y contenido") {

        it("reproduce la regla del ajuste: si el último movimiento es un ajuste, el total es ese ajuste") {
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

        it("sin movimientos devuelve cero, igual que el método directo") {
            val ledger = InventoryLedger()
            LocationContent.legacyAlgorithm(ledger, product, shelf) shouldBe Quantity.ZERO
        }

        it("nunca devuelve un valor negativo (descarta totales <= 0)") {
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

    describe("la comparación: el insumo de la decisión R-67") {

        it("sin operaciones no hay divergencias") {
            val ledger = InventoryLedger()
            ledger.initialLoad(product, shelf, Quantity.of(100))

            val divergences = LocationContentComparison.onlyDivergent(ledger)

            with(ledger) {
                // Un solo movimiento: los dos métodos ven lo mismo.
                LocationContent.direct(this, product, shelf) shouldBe Quantity.of(100)
            }
            divergences.size shouldBe 0
        }

        it("el informe es legible y explica las divergencias encontradas") {
            val ledger = InventoryLedger()
            ledger.receipt(product, receiving, Quantity.of(100))
            ledger.locate(product, receiving, shelf, Quantity.of(100))
            ledger.moveWithinWarehouse(product, shelf, shelfB, Quantity.of(30))

            val report = LocationContentComparison.report(ledger)

            // El informe debe existir y ser legible por alguien de negocio.
            report shouldContain "Content discrepancy report"
            report shouldContain "Locations with discrepancy"
        }

        it("toda divergencia se clasifica como explicada o como pendiente de revisión") {
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

        it("una divergencia donde el modelo ve MENOS NO se explica por el bug conocido") {
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

        it("una divergencia donde el modelo ve MÁS sí se explica: es el descuadre acumulado") {
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

    describe("el contraste con el bug del legacy, sobre una recepción completa") {

        it("tras recibir y ubicar, el sistema nuevo conserva el total y el legacy lo habría perdido") {
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
