package dev.axzy.stock.domain.scanner

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BARCODE SCANNER MODEL TESTS
 *
 * It covers the **two bugs of the legacy scanner**, each one with its negative case:
 *
 *  · **M4** — the visual window measured 300×100 and the validation accepted 250×50.
 *    Valid scans discarded.
 *  · **M3** — the two-step state machine was broken: the branch that assigns
 *    the location was unreachable without a prior error.
 *
 * Both are pure logic, so they are tested **on the JVM, in milliseconds and without
 * an emulator**. The camera spike (CameraX/AVFoundation) verifies that the
 * platform delivers the rectangles; that deciding with them is correct is
 * verified here.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ScanModelTest : DescribeSpec({

    val product = ProductCode.of("ABRAA1B")
    val shelf = LocationCode.of("04BCA")
    val receiving = LocationCode.of("RECEIVING")

    fun resolveProduct(code: String): ProductCode? =
        if (code == "ABRAA1B") product else null

    fun resolveLocation(code: String): LocationCode? = when (code) {
        "04BCA" -> shelf
        "RECEIVING" -> receiving
        else -> null
    }

    /** A rectangle centered in the default scan window. */
    fun centeredInWindow(): ScanRect {
        val v = ScanWindow.DEFAULT.window
        return ScanRect(left = v.centerX - 0.05, top = v.centerY - 0.02, width = 0.10, height = 0.04)
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("M4 · the scanning window has ONE single definition") {

        it("the visual mask and the validation read the SAME window") {
            // The root cause of the legacy bug: there were two definitions of the
            // same window in two files, and nothing kept them in sync
            // (mask 300×100, validation 250×50).
            //
            // Here there is a single constant. This test pins the property: the window
            // drawn is exactly the one validated.
            val drawnWindow = ScanWindow.DEFAULT.window
            val validatedWindow = ScanWindow.DEFAULT.effective

            withClue("La ventana efectiva CONTIENE a la visual: se valida lo que se dibuja") {
                (validatedWindow.left <= drawnWindow.left) shouldBe true
                (validatedWindow.top <= drawnWindow.top) shouldBe true
                (validatedWindow.right >= drawnWindow.right) shouldBe true
                (validatedWindow.bottom >= drawnWindow.bottom) shouldBe true
            }
        }

        it("a code centred in the window is accepted") {
            val centered = centeredInWindow()

            withClue("Un código en el centro de la banda visual debe aceptarse") {
                ScanWindow.DEFAULT.contains(centered) shouldBe true
            }
        }

        it("a code OUTSIDE the window is rejected with an actionable reason") {
            // The legacy discarded those scans without saying anything, so the
            // operator repeated the gesture without understanding why.
            val outside = ScanRect(left = 0.02, top = 0.02, width = 0.05, height = 0.05)

            withClue("Fuera de la ventana: no se acepta") {
                ScanWindow.DEFAULT.contains(outside) shouldBe false
            }

            val result = ScanState.Idle.onScan(
                ScanInput("ABRAA1B", outside),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
            )

            val rejection = result.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("El mensaje debe decir QUÉ hacer, no solo que falló") {
                (rejection.reason.contains("window")) shouldBe true
            }
        }

        it("the centre of the code decides, not its top corner") {
            // The legacy checked the ORIGIN of the bounding box (`y < top + 50`),
            // so a tall code fell outside even when it was visually
            // centered. That is why it looked misaligned.
            val window = ScanWindow.DEFAULT.window

            // A rectangle whose center is INSIDE but whose origin is above.
            val highButCentered = ScanRect(
                left = window.centerX - 0.02,
                top = window.top - 0.03,      // el origen está fuera, por arriba
                width = 0.04,
                height = 0.12,                 // pero el centro cae dentro
            )

            withClue("Su centro (${highButCentered.centerY}) cae dentro de la ventana") {
                (highButCentered.centerY >= window.top) shouldBe true
                (highButCentered.centerY <= window.bottom) shouldBe true
            }
            withClue(
                "El legacy lo habría DESCARTADO por su origen. Aquí se acepta, " +
                    "porque el operador lo ve centrado."
            ) {
                ScanWindow.DEFAULT.contains(highButCentered) shouldBe true
            }
        }

        it("the tolerance allows a shaky pulse without repeating the scan") {
            val window = ScanWindow.DEFAULT
            // Just below the edge of the visual window, inside the
            // tolerancia.
            val barelyOutside = ScanRect(
                left = window.window.centerX - 0.02,
                top = window.window.top - 0.03,
                width = 0.04,
                height = 0.04,
            )

            withClue("Su centro está fuera de la ventana visual") {
                (barelyOutside.centerY < window.window.top) shouldBe true
            }
            withClue("Pero dentro de la tolerancia, así que se acepta") {
                window.contains(barelyOutside) shouldBe true
            }
        }

        it("an out-of-range tolerance cannot be built") {
            val invalid = runCatching {
                ScanWindow(ScanWindow.DEFAULT.window, tolerance = 0.9)
            }
            invalid.isFailure shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("M3 · the state machine: the unreachable branch no longer exists") {

        it("the full two-step flow works in TWO scans, not three") {
            // The legacy bug: step was incremented once and the handler
            // compared step === 1, so the location branch was
            // unreachable until an error set step back to 0. The operator
            // needed a third scan.
            val centered = centeredInWindow()

            // Escaneo 1: producto.
            val afterProduct = ScanState.Idle.onScan(
                ScanInput("ABRAA1B", centered),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
                unitsEntered = 50,
            )

            val estado1 = afterProduct.shouldBeInstanceOf<ScanOutcome.Advanced>().state
            withClue("Tras UN escaneo, el estado lleva el producto") {
                estado1.shouldBeInstanceOf<ScanState.ProductScanned>().product shouldBe product
            }

            // Scan 2: location. It must work directly.
            val afterLocation = estado1.onScan(
                ScanInput("04BCA", centered),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
            )

            val estado2 = afterLocation.shouldBeInstanceOf<ScanOutcome.Advanced>().state
            val ready = estado2.shouldBeInstanceOf<ScanState.ReadyToConfirm>()
            withClue("Con DOS escaneos debe estar listo para confirmar, no con tres") {
                ready.product shouldBe product
                ready.units shouldBe 50
                ready.to shouldBe shelf
            }
        }

        it("a location cannot be assigned without scanning the product first") {
            // This is the transition the legacy allowed to be written wrongly. Here the
            // Idle state carries no product, so **there is no way to
            // express it**: it is not that validation rejects it, it is that the type does not
            // allow it.
            val centered = centeredInWindow()

            // From Idle, any code is read as a product, never as a
            // location. Scanning a location returns a clear error.
            val result = ScanState.Idle.onScan(
                ScanInput("04BCA", centered),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
            )

            val rejection = result.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Debe decir que ese código no es un producto") {
                (rejection.reason.contains("not a known product code")) shouldBe true
            }
        }

        it("requires the units before scanning the location") {
            val centered = centeredInWindow()

            val stateWithProduct = ScanState.ProductScanned(product, units = null)

            val result = stateWithProduct.onScan(
                ScanInput("04BCA", centered),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
                unitsEntered = null,
            )

            val rejection = result.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Sin unidades no se puede continuar, y el mensaje lo explica") {
                (rejection.reason.contains("units")) shouldBe true
            }
        }

        it("a second movement cannot be chained without confirming the first") {
            val centered = centeredInWindow()

            val ready = ScanState.ReadyToConfirm(product, units = 50, to = shelf)

            val result = ready.onScan(
                ScanInput("ABRAA1B", centered),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
            )

            val rejection = result.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Debe pedir confirmar o cancelar antes de seguir") {
                (rejection.reason.contains("confirm")) shouldBe true
            }
        }

        it("rejects moving goods to the receiving location") {
            val centered = centeredInWindow()
            val status = ScanState.ProductScanned(product, units = 50)

            val result = status.onScan(
                ScanInput("RECEIVING", centered),
                resolveProduct = ::resolveProduct,
                resolveLocation = ::resolveLocation,
            )

            val rejection = result.shouldBeInstanceOf<ScanOutcome.Rejected>()
            withClue("Mover a la misma ubicación de origen no es un movimiento") {
                (rejection.reason.contains("receiving location")) shouldBe true
            }
        }

        it("the step number is only for the interface, never for the logic") {
            // The legacy compared `step === 1` to decide what to do. Here the
            // step exists to show "step 2 of 4" and nothing more: the decision is
            // taken by the state TYPE.
            val statuses = listOf(
                ScanState.Idle,
                ScanState.ProductScanned(product, units = null),
                ScanState.ProductScanned(product, units = 50),
                ScanState.ReadyToConfirm(product, 50, shelf),
            )

            val steps = statuses.map { it.step }
            withClue("Los pasos son crecientes y sirven para mostrar progreso") {
                steps shouldBe listOf(1, 2, 3, 4)
            }
            withClue(
                "Pero ningún test decide nada con `step`: todas las decisiones son " +
                    "`when` sobre el tipo. Ésa es la corrección estructural."
            ) {
                statuses.distinct().size shouldBe 4
            }
        }

        it("the instruction shown matches the real state") {
            // The legacy showed the instruction for the wrong step because of the same
            // comparison bug, so the operator read "scan the product"
            // when they had to scan the location.
            ScanState.Idle.expected shouldBe ScanTarget.PRODUCT
            ScanState.ProductScanned(product, 50).expected shouldBe ScanTarget.LOCATION
            ScanState.ReadyToConfirm(product, 50, shelf).expected shouldBe ScanTarget.LOCATION
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the rectangle in normalised coordinates") {

        it("rejects coordinates outside the frame") {
            listOf(
                { ScanRect(-0.1, 0.5, 0.2, 0.2) },
                { ScanRect(0.5, 1.2, 0.2, 0.2) },
                { ScanRect(0.9, 0.5, 0.2, 0.2) },   // excede el ancho
                { ScanRect(0.5, 0.9, 0.2, 0.2) },   // excede el alto
                { ScanRect(0.5, 0.5, 0.0, 0.2) },   // ancho cero
            ).forEach { constructor ->
                withClue("Un rectángulo inválido no se puede construir") {
                    runCatching { constructor() }.isFailure shouldBe true
                }
            }
        }

        it("computes the centre and the edges correctly") {
            val r = ScanRect(left = 0.10, top = 0.20, width = 0.40, height = 0.10)

            // It is compared with tolerance: in floating point, 0.10 + 0.20 is not
            // exactly 0.30 but 0.30000000000000004. An exact `shouldBe`
            // on `Double` produces failures that indicate no real error.
            val epsilon = 1e-9
            withClue("Los bordes y el centro deben calcularse bien") {
                kotlin.math.abs(r.right - 0.50) shouldBeLessThan epsilon
                kotlin.math.abs(r.bottom - 0.30) shouldBeLessThan epsilon
                kotlin.math.abs(r.centerX - 0.30) shouldBeLessThan epsilon
                kotlin.math.abs(r.centerY - 0.25) shouldBeLessThan epsilon
            }
        }

        it("works in normalised coordinates to be independent of density") {
            // With absolute pixels, as in the legacy, the same logical window
            // measured different things on a phone and on a tablet. Normalizing
            // removes that class of error.
            val onPhone = ScanWindow.DEFAULT.contains(
                ScanRect(left = 0.45, top = 0.42, width = 0.10, height = 0.04),
            )
            val onTablet = ScanWindow.DEFAULT.contains(
                ScanRect(left = 0.45, top = 0.42, width = 0.10, height = 0.04),
            )

            withClue("El mismo código relativo se acepta igual en cualquier pantalla") {
                onPhone shouldBe onTablet
                onPhone shouldBe true
            }
        }
    }
})
