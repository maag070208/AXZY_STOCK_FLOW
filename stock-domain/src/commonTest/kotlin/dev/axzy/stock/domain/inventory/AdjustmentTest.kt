package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SINGLE ADJUSTMENT RULE TESTS
 *
 * **The value of this rule is that there is ONE and not four.** The legacy had a path
 * for each flow —discrepancy, missing picking stock, physical count and damage— and
 * four paths mean four chances to forget the reason or to not
 * check the balance.
 *
 * That is why the tests that matter most are the **negative** ones: that an adjustment
 * cannot be made without a reason, that an increase is not applied without approval, and that the requester
 * cannot approve their own.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AdjustmentTest : DescribeSpec({

    val product = ProductCode.of("ABRAA1B")
    val location = "04-B-C"

    fun create(
        quantity: Long = 10,
        direction: AdjustmentDirection = AdjustmentDirection.DECREASE,
        reason: AdjustmentReason = AdjustmentReason.SHORTAGE,
        origin: AdjustmentOrigin = AdjustmentOrigin.MANUAL_COUNT,
        note: String? = null,
        requester: Long = 7,
        key: String? = null,
    ): DomainResult<AdjustmentRequest> = AdjustmentRequest.create(
        productCode = product,
        locationLabel = location,
        quantity = Quantity.of(quantity),
        direction = direction,
        reason = reason,
        origin = origin,
        note = note,
        requestedBy = requester,
        idempotencyKey = key,
    )

    // ═════════════════════════════════════════════════════════════════════════
    describe("the reason is mandatory, without exceptions") {

        it("the reason is chosen from a closed list and is not typed") {
            // The legacy stored the reason as free text, so
            // "shrinkage", "Shrinkage" and "damaged product" coexisted as three distinct reasons, and
            // no report could add them up.
            val reasons = AdjustmentReason.entries.map { it.displayName }

            withClue("Ocho motivos, todos con nombre para mostrar") {
                reasons.size shouldBe 8
                (reasons.all { it.isNotBlank() }) shouldBe true
            }
        }

        it("THEFT and OTHER also require a written explanation") {
            // Theft has legal implications and someone will have to answer for
            // it. "Other" without an explanation says nothing.
            withClue("Solo esos dos exigen nota") {
                AdjustmentReason.entries.filter { it.requiresNote } shouldBe
                    listOf(AdjustmentReason.THEFT, AdjustmentReason.OTHER)
            }
        }

        it("THEFT without an explanation is rejected with REASON_REQUIRED") {
            val result = create(reason = AdjustmentReason.THEFT, note = null)

            val error = result.leftOrNull().shouldBeInstanceOf<DomainError.ReasonRequired>()
            withClue("El mensaje debe decir POR QUÉ se exige, no solo que falta") {
                (error.message.contains("audit")) shouldBe true
            }
        }

        it("OTHER with a very short note is also rejected") {
            // "I don't know" is not an explanation. The minimum exists so that the note
            // is still useful within a year.
            create(reason = AdjustmentReason.OTHER, note = "no sé")
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.ReasonRequired>()
        }

        it("OTHER with a sufficient explanation is accepted") {
            create(
                reason = AdjustmentReason.OTHER,
                note = "Se rompió el empaque durante el traslado interno",
            ).rightOrNull().shouldBeInstanceOf<AdjustmentRequest>()
        }

        it("a blank note does not count as an explanation") {
            create(reason = AdjustmentReason.THEFT, note = "      ")
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.ReasonRequired>()
        }

        it("a normal reason does NOT require a note") {
            // Requiring it always would make people write "ok" everywhere, and a
            // note that always says "ok" reports nothing.
            create(reason = AdjustmentReason.SHORTAGE, note = null)
                .rightOrNull()
                .shouldBeInstanceOf<AdjustmentRequest>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the quantity is positive: the direction gives the sign") {

        it("a zero quantity is rejected") {
            val error = create(quantity = 0).leftOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()

            withClue("El mensaje debe explicar que la dirección ya lleva el signo") {
                (error.message.contains("direction")) shouldBe true
            }
        }

        it("A NEGATIVE QUANTITY IS REJECTED: it would reverse the movement") {
            // **This was a real hole, and an agent found it by reading the code.**
            //
            // With a negative quantity and direction DECREASE, the engine subtracts a
            // negative number, that is, it ADDS inventory. And `requiresApproval` looks at the
            // DIRECTION, so it would be `false`: stock could be increased without
            // anyone's approval, which is exactly what R-74 prevents.
            //
            // The test that existed only covered ZERO. `Quantity.ofScaled` does not validate the
            // sign, so the negative value reached this point.
            val result = create(quantity = -500, direction = AdjustmentDirection.DECREASE)

            val error = result.leftOrNull().shouldBeInstanceOf<DomainError.InvalidQuantity>()

            withClue("El mensaje debe explicar que invertiría el movimiento") {
                (error.message.contains("reverse")) shouldBe true
            }
        }

        it("a negative quantity does not sneak through as a DECREASE with a note either") {
            // The full case of the hole: a reason that does not require a note, a direction that
            // does not require approval, and a negative quantity. Together they added
            // inventory with nobody approving anything.
            val request = create(
                quantity = -1000,
                direction = AdjustmentDirection.DECREASE,
                reason = AdjustmentReason.SHORTAGE,
            )

            withClue("No debe construirse la solicitud") {
                (request.rightOrNull() == null) shouldBe true
            }
        }

        it("the direction determines the ledger's movement type") {
            // The adjustment does not invent a new type: it uses the ones that already exist in the
            // movement→effect matrix, which has its own tests.
            withClue("Un aumento es ADJUSTMENT_IN") {
                AdjustmentDirection.INCREASE.movementType shouldBe MovementType.ADJUSTMENT_IN
            }
            withClue("Una merma es ADJUSTMENT_OUT") {
                AdjustmentDirection.DECREASE.movementType shouldBe MovementType.ADJUSTMENT_OUT
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("an adjustment requires an identified user") {

        it("without a user it is rejected") {
            // Without it there is no segregation of duties or audit: it is the
            // "who" requirement of the system.
            create(requester = 0)
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.PermissionDenied>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("THE DELIBERATE ASYMMETRY: increasing requires approval, shrinking does not") {

        it("an INCREASE requires approval") {
            // Shrinkage is detected on its own at the next count. An increase without
            // justification is the simplest way to cover up a theft, so
            // it needs another person to sign it.
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            request.requiresApproval shouldBe true
        }

        it("a SHRINKAGE requires no approval, and that is deliberate") {
            // Blocking the correction of an obvious error only makes people
            // stop correcting them, and then the inventory stays wrong.
            val request = create(
                direction = AdjustmentDirection.DECREASE,
                reason = AdjustmentReason.SHORTAGE,
            ).rightOrNull()!!

            request.requiresApproval shouldBe false
        }

        it("an increase WITHOUT an approver stays PENDING, it is not applied") {
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(request, approverId = null)

            val pending = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.PendingApproval>()
            withClue("El motivo debe explicar el porqué de la aprobación, no solo pedirla") {
                (pending.reason.contains("cover up a theft")) shouldBe true
            }
        }

        it("a shrinkage is applied without an approver") {
            val request = create(direction = AdjustmentDirection.DECREASE).rightOrNull()!!

            AdjustmentPolicy.decide(request, approverId = null) shouldBe
                AdjustmentPolicy.Decision.Apply
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("SEGREGATION OF DUTIES: whoever requests does not approve") {

        it("the same user cannot approve their own adjustment") {
            // Decision R-74. It is the control that prevents someone from covering their own
            // error without anyone reviewing it.
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
                requester = 7,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(request, approverId = 7)

            val rejection = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()
            rejection.error.shouldBeInstanceOf<DomainError.SegregationOfDuties>()
        }

        it("another person CAN approve it") {
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
                requester = 7,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(request, approverId = 20) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("the rejection names the user who tried to approve themselves") {
            // A message that only says "not allowed" forces guessing why.
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                requester = 42,
            ).rightOrNull()!!

            val rejection = AdjustmentPolicy.decide(request, approverId = 42)
                .shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()

            withClue("El mensaje debe incluir el id del usuario: ${rejection.error.message}") {
                (rejection.error.message.contains("42")) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the balance cannot be left negative") {

        it("a shrinkage greater than the balance is rejected with the NUMBERS") {
            val request = create(
                quantity = 500,
                direction = AdjustmentDirection.DECREASE,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(
                request,
                available = Quantity.of(100),
            )

            val rejection = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()
            val error = rejection.error.shouldBeInstanceOf<DomainError.InsufficientStock>()

            withClue("El error lleva las cantidades y la ubicación, no solo un texto") {
                error.available shouldBe 100L
                error.requested shouldBe 500L
                // The location on the label, which is what the operator reads: the
                // flat code of the legacy (`04BCA`) does not tell apart receiving nor the
                // unlocated balance.
                error.location shouldBe "04-B-C"
            }
        }

        it("a shrinkage equal to the balance is accepted: it leaves the location at zero") {
            val request = create(
                quantity = 100,
                direction = AdjustmentDirection.DECREASE,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(request, available = Quantity.of(100)) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("without knowing the balance a quantity is not rejected") {
            // The real check is done by the database with its CHECK. Here it is only verified
            // whether the balance is known, to give a better message.
            val request = create(quantity = 500).rightOrNull()!!

            AdjustmentPolicy.decide(request, available = null) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("the balance check does NOT apply to an increase") {
            // An increase cannot leave the balance negative: it adds.
            val request = create(
                quantity = 500,
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(request, approverId = 20, available = Quantity.of(0)) shouldBe
                AdjustmentPolicy.Decision.Apply
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the four legacy sources, unified") {

        it("each source declares which flow it comes from") {
            withClue("Los cuatro flujos que en el legacy tenían su propio camino") {
                AdjustmentOrigin.entries.map { it.displayName } shouldBe listOf(
                    "Invoice discrepancy",
                    "Shortage in fulfillment",
                    "Physical count",
                    "Damage report",
                )
            }
        }

        it("the same reason works with any origin") {
            // **This is the property that unifies the four flows.** In the legacy
            // each one had its own code, so fixing a reason in the
            // discrepancy fixed nothing in the count.
            AdjustmentOrigin.entries.forEach { origin ->
                withClue("Un faltante desde «${origin.displayName}» debe ser válido") {
                    create(
                        reason = AdjustmentReason.SHORTAGE,
                        origin = origin,
                    ).rightOrNull().shouldBeInstanceOf<AdjustmentRequest>()
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the idempotency key is normalised") {

        it("an empty key is stored as null, not as an empty string") {
            // A phone with no signal retries; an empty string as the key would make
            // all the retries look like the same movement.
            val request = create(key = "   ").rightOrNull()!!

            request.idempotencyKey shouldBe null
        }

        it("a key with spaces around it is trimmed") {
            create(key = "  abc-123  ").rightOrNull()!!.idempotencyKey shouldBe "abc-123"
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("varied cases with tabulated data") {

        // A class with a NAME instead of a four-position tuple.
        //
        // With heterogeneous lists, Kotlin infers a common type between
        // `AdjustmentReason`, `AdjustmentDirection`, `String` and `Boolean`, and the
        // result cannot be destructured without annotations everywhere.
        // With names, moreover, the test reads without counting positions.
        data class Case(
            val reason: AdjustmentReason,
            val direction: AdjustmentDirection,
            val note: String?,
            val valid: Boolean,
        )

        withData(
            nameFn = { c ->
                "${c.reason.displayName} / ${c.direction} / nota=${c.note ?: "—"} → " +
                    if (c.valid) "válido" else "rechazado"
            },
            Case(AdjustmentReason.SHORTAGE, AdjustmentDirection.DECREASE, null, true),
            Case(AdjustmentReason.DAMAGE, AdjustmentDirection.DECREASE, null, true),
            Case(AdjustmentReason.COUNT_ERROR, AdjustmentDirection.INCREASE, null, true),
            Case(AdjustmentReason.SUPPLIER_ERROR, AdjustmentDirection.DECREASE, null, true),
            Case(AdjustmentReason.EXPIRED, AdjustmentDirection.DECREASE, null, true),
            Case(AdjustmentReason.RETURN_TO_SUPPLIER, AdjustmentDirection.DECREASE, null, true),
            Case(AdjustmentReason.THEFT, AdjustmentDirection.DECREASE, null, false),
            Case(AdjustmentReason.THEFT, AdjustmentDirection.DECREASE, "Robo del estante 4", true),
            Case(AdjustmentReason.OTHER, AdjustmentDirection.DECREASE, null, false),
            Case(AdjustmentReason.OTHER, AdjustmentDirection.INCREASE, "Error de captura del conteo", true),
        ) { case ->
            val result = create(
                reason = case.reason,
                direction = case.direction,
                note = case.note,
            )

            withClue("«${case.reason.displayName}» con nota=${case.note}") {
                (result.rightOrNull() != null) shouldBe case.valid
            }
        }
    }
})

/**
 * The error, if the result is incorrect.
 *
 * It returns a concrete `DomainError` and not a generic one: it is the ONLY thing the
 * domain produces, and fixing it avoids annotating the type on every call. Before, I left it
 * generic and the compiler could not infer it anywhere.
 *
 * The property of `Left` is called `error` and not `value`.
 */
private fun <A> DomainResult<A>.leftOrNull(): DomainError? =
    (this as? dev.axzy.stock.domain.common.Either.Left<DomainError>)?.error

/** The value, if the result is correct. */
private fun <A> DomainResult<A>.rightOrNull(): A? = getOrNull()
