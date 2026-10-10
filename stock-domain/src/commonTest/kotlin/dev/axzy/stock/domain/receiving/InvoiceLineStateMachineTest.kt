package dev.axzy.stock.domain.receiving

import dev.axzy.stock.domain.common.getOrThrow
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * INVOICE LINE BUSINESS RULE TESTS
 *
 * The three rules the legacy did not have are tested here, and each one includes
 * **the negative case**, which is the one that really matters: it is not enough for the
 * happy path to work, it must be shown that the control **blocks**.
 *
 *   · B1 / R-75: the discrepancy blocks the location
 *   · R-74:      whoever captures does not approve their own discrepancy
 *   · R-80:      the boxes must add up
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InvoiceLineStateMachineTest : DescribeSpec({

    val product = ProductCode.of("ABRAA1B")
    val shelf = LocationCode.of("04BCA")
    val receiving = LocationCode.of("RECEIVING")
    val operator = 10L
    val supervisor = 20L

    /** Line just created: 100 pieces invoiced, 50 per box. */
    fun lineNew(): InvoiceLine = InvoiceLine(
        id = 1L,
        productCode = product,
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = InvoiceLineStatus.PENDING,
    )

    /**
     * Line already counted, with the stated result.
     *
     * The boxes are the **complete** ones that fit in what was counted: counting 80 pieces
     * with boxes of 50 is 1 box and 30 loose. The remainder is a shortage
     * that is legitimate, not a capture error.
     */
    fun lineCaptured(quantityPhysical: Long): InvoiceLine =
        lineNew()
            .capture(
                physicalQuantity = Quantity.of(quantityPhysical),
                physicalBoxQuantity = BoxQuantity.of(quantityPhysical / 50),
                capturedBy = operator,
            ).also { result ->
                if (result.isLeft) throw AssertionError(
                    "La captura de $quantityPhysical piezas no debería fallar: " +
                        (result as dev.axzy.stock.domain.common.Either.Left).error
                )
            }.getOrThrow()

    /** Line with a discrepancy already authorized by a supervisor. */
    fun lineApproved(quantityPhysical: Long): InvoiceLine =
        // 90 against 100 invoiced: there is a difference, so it can be approved.
        lineCaptured(quantityPhysical)
            .approveDiscrepancy(approvedBy = supervisor, reason = "SUPPLIER_ERROR")
            .getOrThrow()

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-58 / B-2: `difference` is computed, not stored") {

        it("the difference is zero when the quantity matches") {
            val line = lineCaptured(100)
            withClue("El legacy siempre devolvía 0 en este campo (hallazgo L1)") {
                line.difference shouldBe Quantity.ZERO
            }
        }

        it("the difference is negative when LESS than invoiced arrives") {
            val line = lineCaptured(80)
            withClue("Faltan 20 piezas: la diferencia debe reflejarlo") {
                line.difference shouldBe Quantity.of(-20)
            }
        }

        it("the difference is positive when MORE than invoiced arrives") {
            val line = lineCaptured(120)
            withClue("Sobran 20 piezas: la diferencia debe reflejarlo") {
                line.difference shouldBe Quantity.of(20)
            }
        }

        it("without a count there is no computable difference") {
            lineNew().difference shouldBe null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("B1 / R-75: every discrepancy blocks the location (no threshold)") {

        it("a difference of ONE single piece already blocks the location") {
            // Decision R-75: no threshold. There is no "small" difference that gets through.
            val line = lineCaptured(99)
            line.status shouldBe InvoiceLineStatus.DISCREPANCY

            val result = line.locate(Quantity.of(99), shelf)

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("El error debe ser específico, no un genérico 'acceso denegado'") {
                error.error.shouldBeInstanceOf<DomainError.DiscrepancyNotApproved>()
            }
        }

        it("locating a discrepant line does NOT modify the line") {
            val line = lineCaptured(99)
            val result = line.locate(Quantity.of(99), shelf)

            withClue("La línea original debe seguir intacta: la operación no tuvo efecto") {
                line.locatedQuantity shouldBe Quantity.ZERO
                line.status shouldBe InvoiceLineStatus.DISCREPANCY
            }
            result.isLeft shouldBe true
        }

        it("a CAPTURED line without a difference can be located") {
            val line = lineCaptured(100)
            line.status shouldBe InvoiceLineStatus.CAPTURED

            val located = line.locate(Quantity.of(100), shelf).getOrThrow()

            located.status shouldBe InvoiceLineStatus.LOCATED
            located.locatedQuantity shouldBe Quantity.of(100)
            located.location shouldBe shelf
        }

        it("locating partially leaves the line pending for the rest") {
            val line = lineCaptured(100)
            val partial = line.locate(Quantity.of(40), shelf).getOrThrow()

            withClue("Ubicar 40 de 100 no cierra la línea, y tampoco la aprueba") {
                // Still counted: there was no discrepancy to approve, and the database
                // forbids `APPROVED` without an approver.
                partial.status shouldBe InvoiceLineStatus.CAPTURED
                partial.pendingToLocate shouldBe Quantity.of(60)
            }

            val complete = partial.locate(Quantity.of(60), shelf).getOrThrow()
            withClue("Al completar las 100, la línea queda LOCALIZADA") {
                complete.status shouldBe InvoiceLineStatus.LOCATED
            }
        }

        it("locating partially an approved line leaves it approved") {
            // 90 counted of 100 invoiced: discrepancy approved by the supervisor.
            val line = lineApproved(90)
            val partial = line.locate(Quantity.of(30), shelf).getOrThrow()

            withClue("Sigue aprobada —el supervisor ya autorizó esta línea— y no localizada") {
                partial.status shouldBe InvoiceLineStatus.APPROVED
                partial.pendingToLocate shouldBe Quantity.of(60)
            }
        }

        it("cannot locate more than what is pending") {
            val line = lineCaptured(100)
            val result = line.locate(Quantity.of(150), shelf)

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.ExceedsLineQuantity>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-74: segregation of duties when approving discrepancies") {

        it("whoever counted CANNOT approve their own discrepancy") {
            val line = lineCaptured(80)
            line.capturedBy shouldBe operator

            val result = line.approveDiscrepancy(
                approvedBy = operator,     // ← la misma persona
                reason = "error de conteo",
            )

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("Debe ser un error específico, no un genérico de permisos") {
                error.error.shouldBeInstanceOf<DomainError.SegregationOfDuties>()
            }
        }

        it("the self-approval attempt leaves NO approval trace on the line") {
            val line = lineCaptured(80)
            line.approveDiscrepancy(approvedBy = operator, reason = "error de conteo")

            withClue("approvedBy debe seguir nulo y el estado sin cambiar") {
                line.approvedBy shouldBe null
                line.status shouldBe InvoiceLineStatus.DISCREPANCY
            }
        }

        it("ANOTHER person can approve the discrepancy") {
            val line = lineCaptured(80)

            val approved = line.approveDiscrepancy(
                approvedBy = supervisor,
                reason = "faltante confirmado con el proveedor",
            ).getOrThrow()

            approved.status shouldBe InvoiceLineStatus.APPROVED
            approved.approvedBy shouldBe supervisor
            withClue("La cantidad facturada se ajusta a lo contado: es lo que autoriza el supervisor") {
                approved.invoicedQuantity shouldBe Quantity.of(80)
            }
            withClue("Tras aprobar, la diferencia queda en cero: ya está resuelta") {
                approved.difference shouldBe Quantity.ZERO
            }
        }

        it("an approved line CAN already be located") {
            val approved = lineCaptured(80)
                .approveDiscrepancy(approvedBy = supervisor, reason = "faltante")
                .getOrThrow()

            val located = approved.locate(Quantity.of(80), shelf).getOrThrow()
            located.status shouldBe InvoiceLineStatus.LOCATED
        }

        it("approving requires a reason (invariant I6)") {
            val line = lineCaptured(80)

            listOf("", "   ").forEach { reasonEmpty ->
                val result = line.approveDiscrepancy(supervisor, reasonEmpty)
                val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
                withClue("Un motivo en blanco debe rechazarse") {
                    error.error.shouldBeInstanceOf<DomainError.ReasonRequired>()
                }
            }
        }

        it("rejecting the discrepancy returns it to counting and clears the count") {
            val line = lineCaptured(80)

            val rejected = line.rejectDiscrepancy("el conteo no es fiable, hay que repetirlo")
                .getOrThrow()

            withClue("Vuelve a PENDING y se borra el conteo anterior") {
                rejected.status shouldBe InvoiceLineStatus.PENDING
                rejected.physicalQuantity shouldBe null
                rejected.capturedBy shouldBe null
            }
        }

        it("a line that is not in discrepancy cannot be approved") {
            val result = lineCaptured(100).approveDiscrepancy(supervisor, "motivo")
            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.NotPendingApproval>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-80: consistency of packing and of the box count") {

        it("an invoice of 100 pieces with boxes of 30 is REJECTED: an impossible figure") {
            // Nobody packs 3 boxes and 10 loose when the specification says
            // the boxes hold 30. It is an invoice capture error.
            val result = InvoiceLine.validatePackingSpecification(
                invoicedQuantity = Quantity.of(100),
                unitsPerBox = UnitsPerBox.of(30),
            )

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            val boxes = error.error.shouldBeInstanceOf<DomainError.BoxesDoNotDivideEvenly>()
            withClue("El error debe decir cuánto sobra para que se pueda corregir el dato") {
                boxes.remainder shouldBe 10L
                boxes.unitsPerBox shouldBe 30L
            }
        }

        it("an invoice of 90 pieces with boxes of 30 IS ACCEPTED: exactly 3 boxes") {
            InvoiceLine.validatePackingSpecification(
                invoicedQuantity = Quantity.of(90),
                unitsPerBox = UnitsPerBox.of(30),
            ).isRight shouldBe true
        }

        it("an invoice of 20 pieces with boxes of 30 IS ACCEPTED: they are loose goods") {
            withClue("Menos de una caja no tiene remanente que cuadrar") {
                InvoiceLine.validatePackingSpecification(
                    invoicedQuantity = Quantity.of(20),
                    unitsPerBox = UnitsPerBox.of(30),
                ).isRight shouldBe true
            }
        }

        it("COUNTING 80 of a 100-unit invoice with boxes of 50 IS accepted: it is a shortage") {
            // This is the case that a naive validation would block. Counting 80
            // with boxes of 50 is 1 box and 30 loose: a real shortage, which is
            // exactly what the discrepancy flow exists to capture.
            val line = lineCaptured(80)

            withClue("Debe quedar en DISCREPANCY, no fallar la captura") {
                line.status shouldBe InvoiceLineStatus.DISCREPANCY
                line.difference shouldBe Quantity.of(-20)
            }
        }

        it("counting 99 or 120 does not fail either: they are differences, not data errors") {
            lineCaptured(99).status shouldBe InvoiceLineStatus.DISCREPANCY
            lineCaptured(120).status shouldBe InvoiceLineStatus.DISCREPANCY
        }

        it("declaring more boxes than fit in the count IS rejected") {
            // 80 pieces cannot be 2 boxes of 50: that would be 100.
            val result = lineNew().capture(
                physicalQuantity = Quantity.of(80),
                physicalBoxQuantity = BoxQuantity.of(2),
                capturedBy = operator,
            )

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InconsistentBoxCount>()
        }

        it("declaring fewer boxes than fit is accepted: the operator counts loose pieces") {
            // 100 pieces with 50 per box declared as 1 box: 50 remain
            // counted loose. It is a valid description of the count.
            val result = lineNew().capture(
                physicalQuantity = Quantity.of(100),
                physicalBoxQuantity = BoxQuantity.of(1),
                capturedBy = operator,
            )
            result.isRight shouldBe true
        }

        it("a zero or negative quantity is rejected") {
            val result = lineNew().capture(
                physicalQuantity = Quantity.ZERO,
                physicalBoxQuantity = BoxQuantity.ZERO,
                capturedBy = operator,
            )
            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InvalidQuantity>()
        }
    }

    describe("invalid transitions: the legacy allowed illegal jumps") {

        it("a line cannot be located without being counted") {
            val result = lineNew().locate(Quantity.of(10), shelf)
            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            val transition = error.error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            withClue("El mensaje debe guiar al usuario, no solo fallar") {
                transition.message shouldBe "The goods must be counted before they are located"
            }
        }

        it("a line already located cannot be counted") {
            val located = lineCaptured(100).locate(Quantity.of(100), shelf).getOrThrow()
            val result = located.capture(Quantity.of(100), BoxQuantity.of(2), operator)

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("a line with located goods cannot be voided (R-57/R-68)") {
            val located = lineCaptured(100).locate(Quantity.of(100), shelf).getOrThrow()
            val result = located.cancel("ya no se necesita")

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("Con efecto en inventario no se anula: se compensa con un movimiento") {
                error.error.shouldBeInstanceOf<DomainError.CannotDeleteWithInventoryEffect>()
            }
        }

        it("only a pending line can be assigned") {
            lineNew().assign(userId = 7).isRight shouldBe true
            val result = lineCaptured(100).assign(userId = 7)
            result.isLeft shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("states: derived queries instead of string comparisons") {

        it("DISCREPANCY does not allow locating and blocks closing") {
            val status = InvoiceLineStatus.DISCREPANCY
            status.allowsLocating shouldBe false
            status.blocksInvoiceClosing shouldBe true
        }

        it("LOCATED allows locating more but does not block closing") {
            val status = InvoiceLineStatus.LOCATED
            status.allowsLocating shouldBe true
            status.blocksInvoiceClosing shouldBe false
        }

        it("CANCELLED allows nothing") {
            val status = InvoiceLineStatus.CANCELLED
            status.allowsLocating shouldBe false
            status.blocksInvoiceClosing shouldBe false
        }

        it("every state has a defined response (the `when` is exhaustive)") {
            InvoiceLineStatus.entries.forEach { status ->
                withClue("El estado ${status.name} debe tener ambas consultas definidas") {
                    // If a case is missing in the `when`s above, this does not compile.
                    status.allowsLocating
                    status.blocksInvoiceClosing
                }
            }
        }
    }
})
