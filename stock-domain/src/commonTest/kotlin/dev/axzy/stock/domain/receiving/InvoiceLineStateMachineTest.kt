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
 * TESTS DE LAS REGLAS DE NEGOCIO DE LA LÍNEA DE FACTURA
 *
 * Las tres reglas que el legacy no tenía se prueban aquí, y cada una incluye
 * **el caso negativo**, que es el que de verdad importa: no basta con que el
 * flujo feliz funcione, hay que demostrar que el control **bloquea**.
 *
 *   · B1 / R-75: la discrepancia bloquea la ubicación
 *   · R-74:      quien captura no aprueba su propia discrepancia
 *   · R-80:      las cajas deben cuadrar
 * ═══════════════════════════════════════════════════════════════════════════
 */
class InvoiceLineStateMachineTest : DescribeSpec({

    val product = ProductCode.of("ABRAA1B")
    val shelf = LocationCode.of("04BCA")
    val receiving = LocationCode.of("RECEPCION")
    val operator = 10L
    val supervisor = 20L

    /** Línea recién dada de alta: 100 piezas facturadas, 50 por caja. */
    fun lineNew(): InvoiceLine = InvoiceLine(
        id = 1L,
        productCode = product,
        invoicedQuantity = Quantity.of(100),
        unitsPerBox = UnitsPerBox.of(50),
        status = InvoiceLineStatus.PENDING,
    )

    /**
     * Línea ya contada, con el resultado indicado.
     *
     * Las cajas son las **completas** que caben en lo contado: contar 80 piezas
     * con cajas de 50 es 1 caja y 30 sueltas. El remanente es un faltante
     * legítimo, no un error de captura.
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

    /** Línea con una discrepancia ya autorizada por un supervisor. */
    fun lineApproved(quantityPhysical: Long): InvoiceLine =
        // 90 contra 100 facturadas: hay diferencia, así que se puede aprobar.
        lineCaptured(quantityPhysical)
            .approveDiscrepancy(approvedBy = supervisor, reason = "SUPPLIER_ERROR")
            .getOrThrow()

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-58 / B-2: `difference` se calcula, no se almacena") {

        it("la diferencia es cero cuando la cantidad coincide") {
            val line = lineCaptured(100)
            withClue("El legacy siempre devolvía 0 en este campo (hallazgo L1)") {
                line.difference shouldBe Quantity.ZERO
            }
        }

        it("la diferencia es negativa cuando llega MENOS de lo facturado") {
            val line = lineCaptured(80)
            withClue("Faltan 20 piezas: la diferencia debe reflejarlo") {
                line.difference shouldBe Quantity.of(-20)
            }
        }

        it("la diferencia es positiva cuando llega MÁS de lo facturado") {
            val line = lineCaptured(120)
            withClue("Sobran 20 piezas: la diferencia debe reflejarlo") {
                line.difference shouldBe Quantity.of(20)
            }
        }

        it("sin captura no hay diferencia calculable") {
            lineNew().difference shouldBe null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("B1 / R-75: toda discrepancia bloquea la ubicación (sin umbral)") {

        it("una diferencia de UNA sola pieza ya bloquea la ubicación") {
            // Decisión R-75: sin umbral. No hay diferencia "pequeña" que pase.
            val line = lineCaptured(99)
            line.status shouldBe InvoiceLineStatus.DISCREPANCY

            val result = line.locate(Quantity.of(99), shelf)

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("El error debe ser específico, no un genérico 'acceso denegado'") {
                error.error.shouldBeInstanceOf<DomainError.DiscrepancyNotApproved>()
            }
        }

        it("ubicar una línea en discrepancia NO modifica la línea") {
            val line = lineCaptured(99)
            val result = line.locate(Quantity.of(99), shelf)

            withClue("La línea original debe seguir intacta: la operación no tuvo efecto") {
                line.locatedQuantity shouldBe Quantity.ZERO
                line.status shouldBe InvoiceLineStatus.DISCREPANCY
            }
            result.isLeft shouldBe true
        }

        it("una línea CAPTURADA sin diferencia sí se puede ubicar") {
            val line = lineCaptured(100)
            line.status shouldBe InvoiceLineStatus.CAPTURED

            val located = line.locate(Quantity.of(100), shelf).getOrThrow()

            located.status shouldBe InvoiceLineStatus.LOCATED
            located.locatedQuantity shouldBe Quantity.of(100)
            located.location shouldBe shelf
        }

        it("ubicar parcialmente deja la línea pendiente del resto") {
            val line = lineCaptured(100)
            val partial = line.locate(Quantity.of(40), shelf).getOrThrow()

            withClue("Ubicar 40 de 100 no cierra la línea, y tampoco la aprueba") {
                // Sigue contada: no hubo ninguna discrepancia que aprobar, y la base
                // prohíbe `APPROVED` sin aprobador.
                partial.status shouldBe InvoiceLineStatus.CAPTURED
                partial.pendingToLocate shouldBe Quantity.of(60)
            }

            val complete = partial.locate(Quantity.of(60), shelf).getOrThrow()
            withClue("Al completar las 100, la línea queda LOCALIZADA") {
                complete.status shouldBe InvoiceLineStatus.LOCATED
            }
        }

        it("ubicar parcialmente una línea aprobada la deja aprobada") {
            // 90 contadas de 100 facturadas: discrepancia aprobada por el supervisor.
            val line = lineApproved(90)
            val partial = line.locate(Quantity.of(30), shelf).getOrThrow()

            withClue("Sigue aprobada —el supervisor ya autorizó esta línea— y no localizada") {
                partial.status shouldBe InvoiceLineStatus.APPROVED
                partial.pendingToLocate shouldBe Quantity.of(60)
            }
        }

        it("no se puede ubicar más de lo pendiente") {
            val line = lineCaptured(100)
            val result = line.locate(Quantity.of(150), shelf)

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.ExceedsLineQuantity>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-74: segregación de funciones al aprobar discrepancias") {

        it("quien capturó NO puede aprobar su propia discrepancia") {
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

        it("el intento de autoaprobación NO deja rastro de aprobación en la línea") {
            val line = lineCaptured(80)
            line.approveDiscrepancy(approvedBy = operator, reason = "error de conteo")

            withClue("approvedBy debe seguir nulo y el estado sin cambiar") {
                line.approvedBy shouldBe null
                line.status shouldBe InvoiceLineStatus.DISCREPANCY
            }
        }

        it("OTRA persona sí puede aprobar la discrepancia") {
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

        it("una línea aprobada YA se puede ubicar") {
            val approved = lineCaptured(80)
                .approveDiscrepancy(approvedBy = supervisor, reason = "faltante")
                .getOrThrow()

            val located = approved.locate(Quantity.of(80), shelf).getOrThrow()
            located.status shouldBe InvoiceLineStatus.LOCATED
        }

        it("aprobar exige motivo (invariante I6)") {
            val line = lineCaptured(80)

            listOf("", "   ").forEach { reasonEmpty ->
                val result = line.approveDiscrepancy(supervisor, reasonEmpty)
                val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
                withClue("Un motivo en blanco debe rechazarse") {
                    error.error.shouldBeInstanceOf<DomainError.ReasonRequired>()
                }
            }
        }

        it("rechazar la discrepancia la devuelve a captura y limpia el conteo") {
            val line = lineCaptured(80)

            val rejected = line.rejectDiscrepancy("el conteo no es fiable, hay que repetirlo")
                .getOrThrow()

            withClue("Vuelve a PENDING y se borra el conteo anterior") {
                rejected.status shouldBe InvoiceLineStatus.PENDING
                rejected.physicalQuantity shouldBe null
                rejected.capturedBy shouldBe null
            }
        }

        it("no se puede aprobar una línea que no está en discrepancia") {
            val result = lineCaptured(100).approveDiscrepancy(supervisor, "motivo")
            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.NotPendingApproval>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-80: coherencia del empaque y del conteo de cajas") {

        it("una factura de 100 piezas con cajas de 30 se RECHAZA: dato imposible") {
            // Nadie empaca 3 cajas y 10 sueltas cuando la especificación dice
            // que las cajas son de 30. Es un error de captura de la factura.
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

        it("una factura de 90 piezas con cajas de 30 SE ACEPTA: 3 cajas exactas") {
            InvoiceLine.validatePackingSpecification(
                invoicedQuantity = Quantity.of(90),
                unitsPerBox = UnitsPerBox.of(30),
            ).isRight shouldBe true
        }

        it("una factura de 20 piezas con cajas de 30 SE ACEPTA: es mercancía suelta") {
            withClue("Menos de una caja no tiene remanente que cuadrar") {
                InvoiceLine.validatePackingSpecification(
                    invoicedQuantity = Quantity.of(20),
                    unitsPerBox = UnitsPerBox.of(30),
                ).isRight shouldBe true
            }
        }

        it("CONTAR 80 de una factura de 100 con cajas de 50 SÍ se acepta: es un faltante") {
            // Éste es el caso que una validación ingenua bloquearía. Contar 80
            // con cajas de 50 es 1 caja y 30 sueltas: un faltante real, que es
            // exactamente lo que el flujo de discrepancia existe para capturar.
            val line = lineCaptured(80)

            withClue("Debe quedar en DISCREPANCY, no fallar la captura") {
                line.status shouldBe InvoiceLineStatus.DISCREPANCY
                line.difference shouldBe Quantity.of(-20)
            }
        }

        it("contar 99 o 120 tampoco falla: son diferencias, no errores de dato") {
            lineCaptured(99).status shouldBe InvoiceLineStatus.DISCREPANCY
            lineCaptured(120).status shouldBe InvoiceLineStatus.DISCREPANCY
        }

        it("declarar más cajas de las que caben en el conteo SÍ se rechaza") {
            // 80 piezas no pueden ser 2 cajas de 50: serían 100.
            val result = lineNew().capture(
                physicalQuantity = Quantity.of(80),
                physicalBoxQuantity = BoxQuantity.of(2),
                capturedBy = operator,
            )

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InconsistentBoxCount>()
        }

        it("declarar menos cajas de las que caben se acepta: el operador cuenta sueltas") {
            // 100 piezas con 50 por caja declaradas como 1 caja: quedan 50
            // sueltas contadas. Es una descripción válida del conteo.
            val result = lineNew().capture(
                physicalQuantity = Quantity.of(100),
                physicalBoxQuantity = BoxQuantity.of(1),
                capturedBy = operator,
            )
            result.isRight shouldBe true
        }

        it("cantidad cero o negativa se rechaza") {
            val result = lineNew().capture(
                physicalQuantity = Quantity.ZERO,
                physicalBoxQuantity = BoxQuantity.ZERO,
                capturedBy = operator,
            )
            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InvalidQuantity>()
        }
    }

    describe("transiciones inválidas: el legacy permitía saltos ilegales") {

        it("no se puede ubicar una línea sin capturar") {
            val result = lineNew().locate(Quantity.of(10), shelf)
            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            val transition = error.error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            withClue("El mensaje debe guiar al usuario, no solo fallar") {
                transition.message shouldBe "La mercancía debe contarse antes de ubicarse"
            }
        }

        it("no se puede capturar una línea ya ubicada") {
            val located = lineCaptured(100).locate(Quantity.of(100), shelf).getOrThrow()
            val result = located.capture(Quantity.of(100), BoxQuantity.of(2), operator)

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("no se puede anular una línea con mercancía ubicada (R-57/R-68)") {
            val located = lineCaptured(100).locate(Quantity.of(100), shelf).getOrThrow()
            val result = located.cancel("ya no se necesita")

            val error = result.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("Con efecto en inventario no se anula: se compensa con un movimiento") {
                error.error.shouldBeInstanceOf<DomainError.CannotDeleteWithInventoryEffect>()
            }
        }

        it("solo se puede asignar una línea pendiente") {
            lineNew().assign(userId = 7).isRight shouldBe true
            val result = lineCaptured(100).assign(userId = 7)
            result.isLeft shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("estados: consultas derivadas en lugar de comparaciones de strings") {

        it("DISCREPANCY no permite ubicar y bloquea el cierre") {
            val status = InvoiceLineStatus.DISCREPANCY
            status.allowsLocating shouldBe false
            status.blocksInvoiceClosing shouldBe true
        }

        it("LOCATED permite ubicar más pero no bloquea el cierre") {
            val status = InvoiceLineStatus.LOCATED
            status.allowsLocating shouldBe true
            status.blocksInvoiceClosing shouldBe false
        }

        it("CANCELLED no permite nada") {
            val status = InvoiceLineStatus.CANCELLED
            status.allowsLocating shouldBe false
            status.blocksInvoiceClosing shouldBe false
        }

        it("todo estado tiene una respuesta definida (el `when` es exhaustivo)") {
            InvoiceLineStatus.entries.forEach { status ->
                withClue("El estado ${status.name} debe tener ambas consultas definidas") {
                    // Si falta un caso en los `when` de arriba, esto no compila.
                    status.allowsLocating
                    status.blocksInvoiceClosing
                }
            }
        }
    }
})
