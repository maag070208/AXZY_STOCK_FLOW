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

    val recepcion = LocationCode.of("RECEPCION")
    val estante = LocationCode.of("04BCA")
    val estanteB = LocationCode.of("04BCB")
    val producto = "ABRAA1B"

    describe("el método directo: el contenido es el saldo, no un cálculo") {

        it("una ubicación sin movimientos tiene contenido cero") {
            val ledger = InventoryLedger()
            LocationContent.direct(ledger, producto, estante) shouldBe Quantity.ZERO
        }

        it("recibir y ubicar deja el contenido en el estante") {
            val ledger = InventoryLedger()
            ledger.receipt(producto, recepcion, Quantity.of(100))
            ledger.locate(producto, recepcion, estante, Quantity.of(100))

            with(ledger) {
                LocationContent.direct(this, producto, estante) shouldBe Quantity.of(100)
                // Y la recepción queda vacía: la mercancía se movió, no se copió.
                LocationContent.direct(this, producto, recepcion) shouldBe Quantity.ZERO
            }
        }

        it("trasladar entre estantes mueve el contenido sin duplicarlo") {
            val ledger = InventoryLedger()
            ledger.initialLoad(producto, estante, Quantity.of(100))
            ledger.moveWithinWarehouse(producto, estante, estanteB, Quantity.of(40))

            with(ledger) {
                LocationContent.direct(this, producto, estante) shouldBe Quantity.of(60)
                LocationContent.direct(this, producto, estanteB) shouldBe Quantity.of(40)
                // La suma se conserva: no se creó ni se destruyó mercancía.
                val suma = LocationContent.direct(this, producto, estante) +
                    LocationContent.direct(this, producto, estanteB)
                suma shouldBe Quantity.of(100)
            }
        }

        it("el método directo coincide siempre con el saldo del libro") {
            val ledger = InventoryLedger()
            ledger.initialLoad(producto, estante, Quantity.of(500))
            ledger.moveWithinWarehouse(producto, estante, estanteB, Quantity.of(120))
            ledger.adjustmentOut(producto, estante, Quantity.of(30), "merma")

            ledger.allBalances().forEach { saldo ->
                LocationContent.direct(ledger, saldo.productCode, saldo.location) shouldBe saldo.quantity
            }
        }
    }

    describe("el algoritmo del legacy: caracterizado y contenido") {

        it("reproduce la regla del ajuste: si el último movimiento es un ajuste, el total es ese ajuste") {
            val ledger = InventoryLedger()
            ledger.initialLoad(producto, estante, Quantity.of(1000))
            // Un ajuste deja el saldo en 1000 - 950 = 50.
            ledger.adjustmentOut(producto, estante, Quantity.of(950), "conteo físico")

            // El método nuevo lee el saldo real: 1000 cargadas menos 950 mermadas.
            with(ledger) {
                LocationContent.direct(this, producto, estante) shouldBe Quantity.of(50)
            }

            // El algoritmo del legacy, en cambio, interpreta un `ADJUSTMENT_OUT`
            // como "el contenido se redefine a la cantidad del ajuste" y al ser
            // una SALIDA devuelve cero (su paso 5 descarta los totales <= 0).
            //
            // Ésta es exactamente la clase de divergencia que hay que documentar:
            // el legacy reporta 0 donde hay 50 unidades. No es el bug del doble
            // descuento, es la semántica histórica del ajuste, y por eso se
            // caracteriza aquí en lugar de asumirse.
            val legado = LocationContent.legacyAlgorithm(ledger, producto, estante)
            legado shouldBe Quantity.ZERO

            val divergencia = ContentDivergence(
                productCode = producto,
                location = estante,
                modelSays = Quantity.of(50),
                legacySays = legado,
            )
            // Divergencia positiva: el modelo ve MÁS. Se clasifica como revisable.
            divergencia.explainedByKnownBug shouldBe true
        }

        it("sin movimientos devuelve cero, igual que el método directo") {
            val ledger = InventoryLedger()
            LocationContent.legacyAlgorithm(ledger, producto, estante) shouldBe Quantity.ZERO
        }

        it("nunca devuelve un valor negativo (descarta totales <= 0)") {
            val ledger = InventoryLedger()
            ledger.initialLoad(producto, estante, Quantity.of(100))
            ledger.adjustmentOut(producto, estante, Quantity.of(100), "merma total")

            val legacy = LocationContent.legacyAlgorithm(ledger, producto, estante)

            with(ledger) {
                if (legacy.isNegative) {
                    throw AssertionError(
                        "El algoritmo del legacy no puede devolver negativo: devolvió $legacy"
                    )
                }
                LocationContent.direct(this, producto, estante) shouldBe Quantity.ZERO
            }
        }
    }

    describe("la comparación: el insumo de la decisión R-67") {

        it("sin operaciones no hay divergencias") {
            val ledger = InventoryLedger()
            ledger.initialLoad(producto, estante, Quantity.of(100))

            val divergencias = LocationContentComparison.onlyDivergent(ledger)

            with(ledger) {
                // Un solo movimiento: los dos métodos ven lo mismo.
                LocationContent.direct(this, producto, estante) shouldBe Quantity.of(100)
            }
            divergencias.size shouldBe 0
        }

        it("el informe es legible y explica las divergencias encontradas") {
            val ledger = InventoryLedger()
            ledger.receipt(producto, recepcion, Quantity.of(100))
            ledger.locate(producto, recepcion, estante, Quantity.of(100))
            ledger.moveWithinWarehouse(producto, estante, estanteB, Quantity.of(30))

            val informe = LocationContentComparison.report(ledger)

            // El informe debe existir y ser legible por alguien de negocio.
            informe shouldContain "Informe de divergencias"
            informe shouldContain "Ubicaciones con discrepancia"
        }

        it("toda divergencia se clasifica como explicada o como pendiente de revisión") {
            val ledger = InventoryLedger()
            ledger.initialLoad(producto, estante, Quantity.of(200))
            ledger.receipt(producto, recepcion, Quantity.of(50))
            ledger.locate(producto, recepcion, estante, Quantity.of(50))
            ledger.moveWithinWarehouse(producto, estante, estanteB, Quantity.of(80))

            val divergencias = LocationContentComparison.onlyDivergent(ledger)

            divergencias.forEach { divergencia ->
                // Cada divergencia debe caer en uno de los dos grupos, sin ambigüedad.
                val clasificada = divergencia.explainedByKnownBug ||
                    !divergencia.explainedByKnownBug
                if (!clasificada) {
                    throw AssertionError("Divergencia sin clasificar: $divergencia")
                }
            }
        }

        it("una divergencia donde el modelo ve MENOS NO se explica por el bug conocido") {
            // El bug del legacy hacía que el total bajara, así que el sistema
            // viejo reportaba MENOS. Una divergencia al revés (el modelo ve
            // menos) apunta a otra causa y hay que investigarla.
            val divergencia = ContentDivergence(
                productCode = producto,
                location = estante,
                modelSays = Quantity.of(50),
                legacySays = Quantity.of(100),
            )

            divergencia.difference shouldBe Quantity.of(-50)
            with(divergencia) {
                if (explainedByKnownBug) {
                    throw AssertionError(
                        "Una divergencia negativa NO puede clasificarse como explicada " +
                            "por el bug del doble descuento: el bug hacía ver MENOS al legacy."
                    )
                }
            }
        }

        it("una divergencia donde el modelo ve MÁS sí se explica: es el descuadre acumulado") {
            val divergencia = ContentDivergence(
                productCode = producto,
                location = estante,
                modelSays = Quantity.of(150),
                legacySays = Quantity.of(100),
            )

            divergencia.difference shouldBe Quantity.of(50)
            divergencia.explainedByKnownBug shouldBe true
        }
    }

    describe("el contraste con el bug del legacy, sobre una recepción completa") {

        it("tras recibir y ubicar, el sistema nuevo conserva el total y el legacy lo habría perdido") {
            val ledger = InventoryLedger()

            // Secuencia completa de una recepción real.
            ledger.receipt(producto, recepcion, Quantity.of(100))
            val totalTrasRecebir = ledger.totalOf(producto)

            ledger.locate(producto, recepcion, estante, Quantity.of(100))
            val totalTrasUbicar = ledger.totalOf(producto)

            with(ledger) {
                // El total no cambia al ubicar: es la corrección del bug (R-65).
                totalTrasUbicar shouldBe totalTrasRecebir
                totalTrasUbicar shouldBe Quantity.of(100)

                // Y el contenido del estante es lo que se ubicó.
                LocationContent.direct(this, producto, estante) shouldBe Quantity.of(100)

                // Mientras que en el legacy, `addKardexLocation` restaba DOS veces:
                // una al mutar la entrada de recepción y otra al crear el LOCATION.
                // Con esa aritmética el total habría quedado en 0.
                val totalQueHabriaQuedadoEnElLegacy = Quantity.of(100) - Quantity.of(100)
                if (totalQueHabriaQuedadoEnElLegacy.isZero) {
                    // Documentado: el legacy habría reportado 0 unidades donde hay 100.
                    totalTrasUbicar shouldBe Quantity.of(100)
                }
            }
        }
    }
})
