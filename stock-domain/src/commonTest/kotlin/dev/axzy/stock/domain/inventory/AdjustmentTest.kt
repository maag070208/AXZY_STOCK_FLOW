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
 * TESTS DE LA REGLA ÚNICA DE AJUSTE
 *
 * **El valor de esta regla es que hay UNA y no cuatro.** El legacy tenía un camino
 * por cada flujo —discrepancia, faltante de surtido, conteo físico y daños— y
 * cuatro caminos significan cuatro oportunidades de olvidarse del motivo o de no
 * comprobar el saldo.
 *
 * Por eso los tests que más importan son los **negativos**: que no se pueda ajustar
 * sin motivo, que un aumento no se aplique sin aprobación, y que quien pide no
 * pueda aprobar lo suyo.
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
    describe("el motivo es obligatorio, sin excepciones") {

        it("el motivo se elige de una lista cerrada y no se escribe") {
            // El legacy guardaba el motivo como texto libre, así que convivían
            // «merma», «Merma» y «producto dañado» como tres motivos distintos y
            // ningún reporte podía sumarlos.
            val reasons = AdjustmentReason.entries.map { it.displayName }

            withClue("Ocho motivos, todos con nombre para mostrar") {
                reasons.size shouldBe 8
                (reasons.all { it.isNotBlank() }) shouldBe true
            }
        }

        it("ROBO y OTRO exigen además una explicación escrita") {
            // Robo tiene implicaciones legales y alguien tendrá que responder por
            // él. «Otro» sin explicación no dice nada.
            withClue("Solo esos dos exigen nota") {
                AdjustmentReason.entries.filter { it.requiresNote } shouldBe
                    listOf(AdjustmentReason.THEFT, AdjustmentReason.OTHER)
            }
        }

        it("ROBO sin explicación se rechaza con REASON_REQUIRED") {
            val result = create(reason = AdjustmentReason.THEFT, note = null)

            val error = result.leftOrNull().shouldBeInstanceOf<DomainError.ReasonRequired>()
            withClue("El mensaje debe decir POR QUÉ se exige, no solo que falta") {
                (error.message.contains("audit")) shouldBe true
            }
        }

        it("OTRO con una nota muy corta también se rechaza") {
            // «no sé» no es una explicación. El mínimo existe para que la nota
            // sirva dentro de un año.
            create(reason = AdjustmentReason.OTHER, note = "no sé")
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.ReasonRequired>()
        }

        it("OTRO con una explicación suficiente se acepta") {
            create(
                reason = AdjustmentReason.OTHER,
                note = "Se rompió el empaque durante el traslado interno",
            ).rightOrNull().shouldBeInstanceOf<AdjustmentRequest>()
        }

        it("una nota en blanco no cuenta como explicación") {
            create(reason = AdjustmentReason.THEFT, note = "      ")
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.ReasonRequired>()
        }

        it("un motivo normal NO exige nota") {
            // Exigirla siempre haría que la gente escribiera «ok» en todas, y una
            // nota que siempre dice «ok» no informa de nada.
            create(reason = AdjustmentReason.SHORTAGE, note = null)
                .rightOrNull()
                .shouldBeInstanceOf<AdjustmentRequest>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la cantidad es positiva: la dirección dice el signo") {

        it("una cantidad de cero se rechaza") {
            val error = create(quantity = 0).leftOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()

            withClue("El mensaje debe explicar que la dirección ya lleva el signo") {
                (error.message.contains("direction")) shouldBe true
            }
        }

        it("UNA CANTIDAD NEGATIVA SE RECHAZA: invertiría el movimiento") {
            // **Éste era un agujero real, y lo encontró un agente leyendo el código.**
            //
            // Con una cantidad negativa y dirección DECREASE, el motor resta un
            // número negativo, o sea SUMA inventario. Y `requiresApproval` mira la
            // DIRECCIÓN, así que sería `false`: se podría aumentar el stock sin
            // aprobación de nadie, que es justo lo que R-74 impide.
            //
            // El test que había solo cubría el CERO. `Quantity.ofScaled` no valida el
            // signo, así que el negativo llegaba hasta aquí.
            val result = create(quantity = -500, direction = AdjustmentDirection.DECREASE)

            val error = result.leftOrNull().shouldBeInstanceOf<DomainError.InvalidQuantity>()

            withClue("El mensaje debe explicar que invertiría el movimiento") {
                (error.message.contains("reverse")) shouldBe true
            }
        }

        it("una cantidad negativa tampoco se cuela como DECREASE con nota") {
            // El caso completo del agujero: motivo que no exige nota, dirección que
            // no pide aprobación, y cantidad negativa. Los tres juntos sumaban
            // inventario sin que nadie aprobara nada.
            val request = create(
                quantity = -1000,
                direction = AdjustmentDirection.DECREASE,
                reason = AdjustmentReason.SHORTAGE,
            )

            withClue("No debe construirse la solicitud") {
                (request.rightOrNull() == null) shouldBe true
            }
        }

        it("la dirección determina el tipo de movimiento del ledger") {
            // El ajuste no inventa un tipo nuevo: usa los que ya existen en la
            // matriz movimiento→efecto, que tiene sus propios tests.
            withClue("Un aumento es ADJUSTMENT_IN") {
                AdjustmentDirection.INCREASE.movementType shouldBe MovementType.ADJUSTMENT_IN
            }
            withClue("Una merma es ADJUSTMENT_OUT") {
                AdjustmentDirection.DECREASE.movementType shouldBe MovementType.ADJUSTMENT_OUT
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("un ajuste exige un usuario identificado") {

        it("sin usuario se rechaza") {
            // Sin él no hay segregación de funciones ni auditoría: es el requisito
            // «quién» del sistema.
            create(requester = 0)
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.PermissionDenied>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("LA ASIMETRÍA DELIBERADA: aumentar exige aprobación, mermar no") {

        it("un AUMENTO requiere aprobación") {
            // Una merma se detecta sola en el siguiente conteo. Un aumento sin
            // justificar es la forma más sencilla de tapar un robo, así que
            // necesita que otra persona lo firme.
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            request.requiresApproval shouldBe true
        }

        it("una MERMA no requiere aprobación, y es deliberado") {
            // Bloquear la corrección de un error evidente solo hace que la gente
            // deje de corregirlos, y entonces el inventario se queda mal.
            val request = create(
                direction = AdjustmentDirection.DECREASE,
                reason = AdjustmentReason.SHORTAGE,
            ).rightOrNull()!!

            request.requiresApproval shouldBe false
        }

        it("un aumento SIN aprobador queda PENDIENTE, no se aplica") {
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

        it("una merma se aplica sin aprobador") {
            val request = create(direction = AdjustmentDirection.DECREASE).rightOrNull()!!

            AdjustmentPolicy.decide(request, approverId = null) shouldBe
                AdjustmentPolicy.Decision.Apply
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("SEGREGACIÓN DE FUNCIONES: quien pide no aprueba") {

        it("el mismo usuario no puede aprobar su propio ajuste") {
            // Decisión R-74. Es el control que impide que alguien cubra su propio
            // error sin que nadie lo revise.
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
                requester = 7,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(request, approverId = 7)

            val rejection = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()
            rejection.error.shouldBeInstanceOf<DomainError.SegregationOfDuties>()
        }

        it("otra persona SÍ puede aprobarlo") {
            val request = create(
                direction = AdjustmentDirection.INCREASE,
                reason = AdjustmentReason.COUNT_ERROR,
                requester = 7,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(request, approverId = 20) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("el rechazo nombra al usuario que intentó aprobarse") {
            // Un mensaje que solo diga «no permitido» obliga a adivinar por qué.
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
    describe("no se puede dejar el saldo en negativo") {

        it("una merma mayor que el saldo se rechaza con los NÚMEROS") {
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
                // La ubicación de la etiqueta, que es la que el operador lee: el
                // código plano del legacy (`04BCA`) no distingue la recepción ni el
                // saldo sin ubicar.
                error.location shouldBe "04-B-C"
            }
        }

        it("una merma igual al saldo se acepta: deja la ubicación en cero") {
            val request = create(
                quantity = 100,
                direction = AdjustmentDirection.DECREASE,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(request, available = Quantity.of(100)) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("sin conocer el saldo no se rechaza por cantidad") {
            // La comprobación real la hace la base con su CHECK. Aquí se comprueba
            // solo si se conoce el saldo, para dar un mensaje mejor.
            val request = create(quantity = 500).rightOrNull()!!

            AdjustmentPolicy.decide(request, available = null) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("la comprobación de saldo NO aplica a un aumento") {
            // Un aumento no puede dejar el saldo en negativo: suma.
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
    describe("los cuatro orígenes del legacy, unificados") {

        it("cada origen declara de qué flujo viene") {
            withClue("Los cuatro flujos que en el legacy tenían su propio camino") {
                AdjustmentOrigin.entries.map { it.displayName } shouldBe listOf(
                    "Invoice discrepancy",
                    "Shortage in fulfillment",
                    "Physical count",
                    "Damage report",
                )
            }
        }

        it("el mismo motivo funciona con cualquier origen") {
            // **Ésta es la propiedad que unifica los cuatro flujos.** En el legacy
            // cada uno tenía su código, así que arreglar un motivo en la
            // discrepancia no arreglaba nada en el conteo.
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
    describe("la clave de idempotencia se normaliza") {

        it("una clave vacía se guarda como nula, no como cadena vacía") {
            // El móvil sin señal reintenta; una cadena vacía como clave haría que
            // todos los reintentos parecieran el mismo movimiento.
            val request = create(key = "   ").rightOrNull()!!

            request.idempotencyKey shouldBe null
        }

        it("una clave con espacios alrededor se recorta") {
            create(key = "  abc-123  ").rightOrNull()!!.idempotencyKey shouldBe "abc-123"
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("casos variados con datos tabulados") {

        // Una clase con NOMBRE en vez de una tupla de cuatro posiciones.
        //
        // Con listas heterogéneas, Kotlin infiere un tipo común entre
        // `AdjustmentReason`, `AdjustmentDirection`, `String` y `Boolean`, y el
        // resultado no se puede desestructurar sin anotaciones por todas partes.
        // Con nombres, además, el test se lee sin contar posiciones.
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
 * El error, si el resultado es incorrecto.
 *
 * Devuelve `DomainError` concreto y no un genérico: es lo ÚNICO que produce el
 * dominio, y fijarlo evita tener que anotar el tipo en cada llamada. Antes lo dejé
 * genérico y el compilador no podía inferirlo en ningún sitio.
 *
 * La propiedad de `Left` se llama `error` y no `value`.
 */
private fun <A> DomainResult<A>.leftOrNull(): DomainError? =
    (this as? dev.axzy.stock.domain.common.Either.Left<DomainError>)?.error

/** El valor, si el resultado es correcto. */
private fun <A> DomainResult<A>.rightOrNull(): A? = getOrNull()
