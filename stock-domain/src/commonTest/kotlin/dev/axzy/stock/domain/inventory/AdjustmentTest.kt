package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.LocationCode
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

    val producto = ProductCode.of("ABRAA1B")
    val ubicacion = LocationCode.of("04BCA")

    fun crear(
        cantidad: Long = 10,
        direccion: AdjustmentDirection = AdjustmentDirection.DECREASE,
        motivo: AdjustmentReason = AdjustmentReason.SHORTAGE,
        origen: AdjustmentOrigin = AdjustmentOrigin.MANUAL_COUNT,
        nota: String? = null,
        solicitante: Long = 7,
        clave: String? = null,
    ): DomainResult<AdjustmentRequest> = AdjustmentRequest.create(
        productCode = producto,
        locationCode = ubicacion,
        quantity = Quantity.of(cantidad),
        direction = direccion,
        reason = motivo,
        origin = origen,
        note = nota,
        requestedBy = solicitante,
        idempotencyKey = clave,
    )

    // ═════════════════════════════════════════════════════════════════════════
    describe("el motivo es obligatorio, sin excepciones") {

        it("el motivo se elige de una lista cerrada y no se escribe") {
            // El legacy guardaba el motivo como texto libre, así que convivían
            // «merma», «Merma» y «producto dañado» como tres motivos distintos y
            // ningún reporte podía sumarlos.
            val motivos = AdjustmentReason.entries.map { it.displayName }

            withClue("Ocho motivos, todos con nombre para mostrar") {
                motivos.size shouldBe 8
                (motivos.all { it.isNotBlank() }) shouldBe true
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
            val resultado = crear(motivo = AdjustmentReason.THEFT, nota = null)

            val error = resultado.leftOrNull().shouldBeInstanceOf<DomainError.ReasonRequired>()
            withClue("El mensaje debe decir POR QUÉ se exige, no solo que falta") {
                (error.message.contains("auditar")) shouldBe true
            }
        }

        it("OTRO con una nota muy corta también se rechaza") {
            // «no sé» no es una explicación. El mínimo existe para que la nota
            // sirva dentro de un año.
            crear(motivo = AdjustmentReason.OTHER, nota = "no sé")
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.ReasonRequired>()
        }

        it("OTRO con una explicación suficiente se acepta") {
            crear(
                motivo = AdjustmentReason.OTHER,
                nota = "Se rompió el empaque durante el traslado interno",
            ).rightOrNull().shouldBeInstanceOf<AdjustmentRequest>()
        }

        it("una nota en blanco no cuenta como explicación") {
            crear(motivo = AdjustmentReason.THEFT, nota = "      ")
                .leftOrNull()
                .shouldBeInstanceOf<DomainError.ReasonRequired>()
        }

        it("un motivo normal NO exige nota") {
            // Exigirla siempre haría que la gente escribiera «ok» en todas, y una
            // nota que siempre dice «ok» no informa de nada.
            crear(motivo = AdjustmentReason.SHORTAGE, nota = null)
                .rightOrNull()
                .shouldBeInstanceOf<AdjustmentRequest>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la cantidad es positiva: la dirección dice el signo") {

        it("una cantidad de cero se rechaza") {
            val error = crear(cantidad = 0).leftOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()

            withClue("El mensaje debe explicar que la dirección ya lleva el signo") {
                (error.message.contains("dirección")) shouldBe true
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
            crear(solicitante = 0)
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
            val solicitud = crear(
                direccion = AdjustmentDirection.INCREASE,
                motivo = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            solicitud.requiresApproval shouldBe true
        }

        it("una MERMA no requiere aprobación, y es deliberado") {
            // Bloquear la corrección de un error evidente solo hace que la gente
            // deje de corregirlos, y entonces el inventario se queda mal.
            val solicitud = crear(
                direccion = AdjustmentDirection.DECREASE,
                motivo = AdjustmentReason.SHORTAGE,
            ).rightOrNull()!!

            solicitud.requiresApproval shouldBe false
        }

        it("un aumento SIN aprobador queda PENDIENTE, no se aplica") {
            val solicitud = crear(
                direccion = AdjustmentDirection.INCREASE,
                motivo = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(solicitud, approverId = null)

            val pendiente = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.PendingApproval>()
            withClue("El motivo debe explicar el porqué de la aprobación, no solo pedirla") {
                (pendiente.reason.contains("tapar un robo")) shouldBe true
            }
        }

        it("una merma se aplica sin aprobador") {
            val solicitud = crear(direccion = AdjustmentDirection.DECREASE).rightOrNull()!!

            AdjustmentPolicy.decide(solicitud, approverId = null) shouldBe
                AdjustmentPolicy.Decision.Apply
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("SEGREGACIÓN DE FUNCIONES: quien pide no aprueba") {

        it("el mismo usuario no puede aprobar su propio ajuste") {
            // Decisión R-74. Es el control que impide que alguien cubra su propio
            // error sin que nadie lo revise.
            val solicitud = crear(
                direccion = AdjustmentDirection.INCREASE,
                motivo = AdjustmentReason.COUNT_ERROR,
                solicitante = 7,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(solicitud, approverId = 7)

            val rechazo = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()
            rechazo.error.shouldBeInstanceOf<DomainError.SegregationOfDuties>()
        }

        it("otra persona SÍ puede aprobarlo") {
            val solicitud = crear(
                direccion = AdjustmentDirection.INCREASE,
                motivo = AdjustmentReason.COUNT_ERROR,
                solicitante = 7,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(solicitud, approverId = 20) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("el rechazo nombra al usuario que intentó aprobarse") {
            // Un mensaje que solo diga «no permitido» obliga a adivinar por qué.
            val solicitud = crear(
                direccion = AdjustmentDirection.INCREASE,
                solicitante = 42,
            ).rightOrNull()!!

            val rechazo = AdjustmentPolicy.decide(solicitud, approverId = 42)
                .shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()

            withClue("El mensaje debe incluir el id del usuario: ${rechazo.error.message}") {
                (rechazo.error.message.contains("42")) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("no se puede dejar el saldo en negativo") {

        it("una merma mayor que el saldo se rechaza con los NÚMEROS") {
            val solicitud = crear(
                cantidad = 500,
                direccion = AdjustmentDirection.DECREASE,
            ).rightOrNull()!!

            val decision = AdjustmentPolicy.decide(
                solicitud,
                available = Quantity.of(100),
            )

            val rechazo = decision.shouldBeInstanceOf<AdjustmentPolicy.Decision.Reject>()
            val error = rechazo.error.shouldBeInstanceOf<DomainError.InsufficientStock>()

            withClue("El error lleva las cantidades y la ubicación, no solo un texto") {
                error.available shouldBe 100L
                error.requested shouldBe 500L
                error.location shouldBe "04BCA"
            }
        }

        it("una merma igual al saldo se acepta: deja la ubicación en cero") {
            val solicitud = crear(
                cantidad = 100,
                direccion = AdjustmentDirection.DECREASE,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(solicitud, available = Quantity.of(100)) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("sin conocer el saldo no se rechaza por cantidad") {
            // La comprobación real la hace la base con su CHECK. Aquí se comprueba
            // solo si se conoce el saldo, para dar un mensaje mejor.
            val solicitud = crear(cantidad = 500).rightOrNull()!!

            AdjustmentPolicy.decide(solicitud, available = null) shouldBe
                AdjustmentPolicy.Decision.Apply
        }

        it("la comprobación de saldo NO aplica a un aumento") {
            // Un aumento no puede dejar el saldo en negativo: suma.
            val solicitud = crear(
                cantidad = 500,
                direccion = AdjustmentDirection.INCREASE,
                motivo = AdjustmentReason.COUNT_ERROR,
            ).rightOrNull()!!

            AdjustmentPolicy.decide(solicitud, approverId = 20, available = Quantity.of(0)) shouldBe
                AdjustmentPolicy.Decision.Apply
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("los cuatro orígenes del legacy, unificados") {

        it("cada origen declara de qué flujo viene") {
            withClue("Los cuatro flujos que en el legacy tenían su propio camino") {
                AdjustmentOrigin.entries.map { it.displayName } shouldBe listOf(
                    "Discrepancia de factura",
                    "Faltante en surtido",
                    "Conteo físico",
                    "Reporte de daño",
                )
            }
        }

        it("el mismo motivo funciona con cualquier origen") {
            // **Ésta es la propiedad que unifica los cuatro flujos.** En el legacy
            // cada uno tenía su código, así que arreglar un motivo en la
            // discrepancia no arreglaba nada en el conteo.
            AdjustmentOrigin.entries.forEach { origen ->
                withClue("Un faltante desde «${origen.displayName}» debe ser válido") {
                    crear(
                        motivo = AdjustmentReason.SHORTAGE,
                        origen = origen,
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
            val solicitud = crear(clave = "   ").rightOrNull()!!

            solicitud.idempotencyKey shouldBe null
        }

        it("una clave con espacios alrededor se recorta") {
            crear(clave = "  abc-123  ").rightOrNull()!!.idempotencyKey shouldBe "abc-123"
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
        data class Caso(
            val motivo: AdjustmentReason,
            val direccion: AdjustmentDirection,
            val nota: String?,
            val valido: Boolean,
        )

        withData(
            nameFn = { c ->
                "${c.motivo.displayName} / ${c.direccion} / nota=${c.nota ?: "—"} → " +
                    if (c.valido) "válido" else "rechazado"
            },
            Caso(AdjustmentReason.SHORTAGE, AdjustmentDirection.DECREASE, null, true),
            Caso(AdjustmentReason.DAMAGE, AdjustmentDirection.DECREASE, null, true),
            Caso(AdjustmentReason.COUNT_ERROR, AdjustmentDirection.INCREASE, null, true),
            Caso(AdjustmentReason.SUPPLIER_ERROR, AdjustmentDirection.DECREASE, null, true),
            Caso(AdjustmentReason.EXPIRED, AdjustmentDirection.DECREASE, null, true),
            Caso(AdjustmentReason.RETURN_TO_SUPPLIER, AdjustmentDirection.DECREASE, null, true),
            Caso(AdjustmentReason.THEFT, AdjustmentDirection.DECREASE, null, false),
            Caso(AdjustmentReason.THEFT, AdjustmentDirection.DECREASE, "Robo del estante 4", true),
            Caso(AdjustmentReason.OTHER, AdjustmentDirection.DECREASE, null, false),
            Caso(AdjustmentReason.OTHER, AdjustmentDirection.INCREASE, "Error de captura del conteo", true),
        ) { caso ->
            val resultado = crear(
                motivo = caso.motivo,
                direccion = caso.direccion,
                nota = caso.nota,
            )

            withClue("«${caso.motivo.displayName}» con nota=${caso.nota}") {
                (resultado.rightOrNull() != null) shouldBe caso.valido
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
