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

    val producto = ProductCode.of("ABRAA1B")
    val estante = LocationCode.of("04BCA")
    val recepcion = LocationCode.of("RECEPCION")
    val operador = 10L
    val supervisor = 20L

    /** Línea recién dada de alta: 100 piezas facturadas, 50 por caja. */
    fun lineaNueva(): InvoiceLine = InvoiceLine(
        id = 1L,
        productCode = producto,
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
    fun lineaCapturada(cantidadFisica: Long): InvoiceLine =
        lineaNueva()
            .capture(
                physicalQuantity = Quantity.of(cantidadFisica),
                physicalBoxQuantity = BoxQuantity.of(cantidadFisica / 50),
                capturedBy = operador,
            ).also { resultado ->
                if (resultado.isLeft) throw AssertionError(
                    "La captura de $cantidadFisica piezas no debería fallar: " +
                        (resultado as dev.axzy.stock.domain.common.Either.Left).error
                )
            }.getOrThrow()

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-58 / B-2: `difference` se calcula, no se almacena") {

        it("la diferencia es cero cuando la cantidad coincide") {
            val linea = lineaCapturada(100)
            withClue("El legacy siempre devolvía 0 en este campo (hallazgo L1)") {
                linea.difference shouldBe Quantity.ZERO
            }
        }

        it("la diferencia es negativa cuando llega MENOS de lo facturado") {
            val linea = lineaCapturada(80)
            withClue("Faltan 20 piezas: la diferencia debe reflejarlo") {
                linea.difference shouldBe Quantity.of(-20)
            }
        }

        it("la diferencia es positiva cuando llega MÁS de lo facturado") {
            val linea = lineaCapturada(120)
            withClue("Sobran 20 piezas: la diferencia debe reflejarlo") {
                linea.difference shouldBe Quantity.of(20)
            }
        }

        it("sin captura no hay diferencia calculable") {
            lineaNueva().difference shouldBe null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("B1 / R-75: toda discrepancia bloquea la ubicación (sin umbral)") {

        it("una diferencia de UNA sola pieza ya bloquea la ubicación") {
            // Decisión R-75: sin umbral. No hay diferencia "pequeña" que pase.
            val linea = lineaCapturada(99)
            linea.status shouldBe InvoiceLineStatus.DISCREPANCY

            val resultado = linea.locate(Quantity.of(99), estante)

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("El error debe ser específico, no un genérico 'acceso denegado'") {
                error.error.shouldBeInstanceOf<DomainError.DiscrepancyNotApproved>()
            }
        }

        it("ubicar una línea en discrepancia NO modifica la línea") {
            val linea = lineaCapturada(99)
            val resultado = linea.locate(Quantity.of(99), estante)

            withClue("La línea original debe seguir intacta: la operación no tuvo efecto") {
                linea.locatedQuantity shouldBe Quantity.ZERO
                linea.status shouldBe InvoiceLineStatus.DISCREPANCY
            }
            resultado.isLeft shouldBe true
        }

        it("una línea CAPTURADA sin diferencia sí se puede ubicar") {
            val linea = lineaCapturada(100)
            linea.status shouldBe InvoiceLineStatus.CAPTURED

            val ubicada = linea.locate(Quantity.of(100), estante).getOrThrow()

            ubicada.status shouldBe InvoiceLineStatus.LOCATED
            ubicada.locatedQuantity shouldBe Quantity.of(100)
            ubicada.location shouldBe estante
        }

        it("ubicar parcialmente deja la línea pendiente del resto") {
            val linea = lineaCapturada(100)
            val parcial = linea.locate(Quantity.of(40), estante).getOrThrow()

            withClue("Ubicar 40 de 100 no cierra la línea") {
                parcial.status shouldBe InvoiceLineStatus.APPROVED
                parcial.pendingToLocate shouldBe Quantity.of(60)
            }

            val completa = parcial.locate(Quantity.of(60), estante).getOrThrow()
            withClue("Al completar las 100, la línea queda LOCALIZADA") {
                completa.status shouldBe InvoiceLineStatus.LOCATED
            }
        }

        it("no se puede ubicar más de lo pendiente") {
            val linea = lineaCapturada(100)
            val resultado = linea.locate(Quantity.of(150), estante)

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.ExceedsLineQuantity>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-74: segregación de funciones al aprobar discrepancias") {

        it("quien capturó NO puede aprobar su propia discrepancia") {
            val linea = lineaCapturada(80)
            linea.capturedBy shouldBe operador

            val resultado = linea.approveDiscrepancy(
                approvedBy = operador,     // ← la misma persona
                reason = "error de conteo",
            )

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("Debe ser un error específico, no un genérico de permisos") {
                error.error.shouldBeInstanceOf<DomainError.SegregationOfDuties>()
            }
        }

        it("el intento de autoaprobación NO deja rastro de aprobación en la línea") {
            val linea = lineaCapturada(80)
            linea.approveDiscrepancy(approvedBy = operador, reason = "error de conteo")

            withClue("approvedBy debe seguir nulo y el estado sin cambiar") {
                linea.approvedBy shouldBe null
                linea.status shouldBe InvoiceLineStatus.DISCREPANCY
            }
        }

        it("OTRA persona sí puede aprobar la discrepancia") {
            val linea = lineaCapturada(80)

            val aprobada = linea.approveDiscrepancy(
                approvedBy = supervisor,
                reason = "faltante confirmado con el proveedor",
            ).getOrThrow()

            aprobada.status shouldBe InvoiceLineStatus.APPROVED
            aprobada.approvedBy shouldBe supervisor
            withClue("La cantidad facturada se ajusta a lo contado: es lo que autoriza el supervisor") {
                aprobada.invoicedQuantity shouldBe Quantity.of(80)
            }
            withClue("Tras aprobar, la diferencia queda en cero: ya está resuelta") {
                aprobada.difference shouldBe Quantity.ZERO
            }
        }

        it("una línea aprobada YA se puede ubicar") {
            val aprobada = lineaCapturada(80)
                .approveDiscrepancy(approvedBy = supervisor, reason = "faltante")
                .getOrThrow()

            val ubicada = aprobada.locate(Quantity.of(80), estante).getOrThrow()
            ubicada.status shouldBe InvoiceLineStatus.LOCATED
        }

        it("aprobar exige motivo (invariante I6)") {
            val linea = lineaCapturada(80)

            listOf("", "   ").forEach { motivoVacio ->
                val resultado = linea.approveDiscrepancy(supervisor, motivoVacio)
                val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
                withClue("Un motivo en blanco debe rechazarse") {
                    error.error.shouldBeInstanceOf<DomainError.ReasonRequired>()
                }
            }
        }

        it("rechazar la discrepancia la devuelve a captura y limpia el conteo") {
            val linea = lineaCapturada(80)

            val rechazada = linea.rejectDiscrepancy("el conteo no es fiable, hay que repetirlo")
                .getOrThrow()

            withClue("Vuelve a PENDING y se borra el conteo anterior") {
                rechazada.status shouldBe InvoiceLineStatus.PENDING
                rechazada.physicalQuantity shouldBe null
                rechazada.capturedBy shouldBe null
            }
        }

        it("no se puede aprobar una línea que no está en discrepancia") {
            val resultado = lineaCapturada(100).approveDiscrepancy(supervisor, "motivo")
            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.NotPendingApproval>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("R-80: coherencia del empaque y del conteo de cajas") {

        it("una factura de 100 piezas con cajas de 30 se RECHAZA: dato imposible") {
            // Nadie empaca 3 cajas y 10 sueltas cuando la especificación dice
            // que las cajas son de 30. Es un error de captura de la factura.
            val resultado = InvoiceLine.validatePackingSpecification(
                invoicedQuantity = Quantity.of(100),
                unitsPerBox = UnitsPerBox.of(30),
            )

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            val cajas = error.error.shouldBeInstanceOf<DomainError.BoxesDoNotDivideEvenly>()
            withClue("El error debe decir cuánto sobra para que se pueda corregir el dato") {
                cajas.remainder shouldBe 10L
                cajas.unitsPerBox shouldBe 30L
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
            val linea = lineaCapturada(80)

            withClue("Debe quedar en DISCREPANCY, no fallar la captura") {
                linea.status shouldBe InvoiceLineStatus.DISCREPANCY
                linea.difference shouldBe Quantity.of(-20)
            }
        }

        it("contar 99 o 120 tampoco falla: son diferencias, no errores de dato") {
            lineaCapturada(99).status shouldBe InvoiceLineStatus.DISCREPANCY
            lineaCapturada(120).status shouldBe InvoiceLineStatus.DISCREPANCY
        }

        it("declarar más cajas de las que caben en el conteo SÍ se rechaza") {
            // 80 piezas no pueden ser 2 cajas de 50: serían 100.
            val resultado = lineaNueva().capture(
                physicalQuantity = Quantity.of(80),
                physicalBoxQuantity = BoxQuantity.of(2),
                capturedBy = operador,
            )

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InconsistentBoxCount>()
        }

        it("declarar menos cajas de las que caben se acepta: el operador cuenta sueltas") {
            // 100 piezas con 50 por caja declaradas como 1 caja: quedan 50
            // sueltas contadas. Es una descripción válida del conteo.
            val resultado = lineaNueva().capture(
                physicalQuantity = Quantity.of(100),
                physicalBoxQuantity = BoxQuantity.of(1),
                capturedBy = operador,
            )
            resultado.isRight shouldBe true
        }

        it("cantidad cero o negativa se rechaza") {
            val resultado = lineaNueva().capture(
                physicalQuantity = Quantity.ZERO,
                physicalBoxQuantity = BoxQuantity.ZERO,
                capturedBy = operador,
            )
            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InvalidQuantity>()
        }
    }

    describe("transiciones inválidas: el legacy permitía saltos ilegales") {

        it("no se puede ubicar una línea sin capturar") {
            val resultado = lineaNueva().locate(Quantity.of(10), estante)
            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            val transicion = error.error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            withClue("El mensaje debe guiar al usuario, no solo fallar") {
                transicion.message shouldBe "La mercancía debe contarse antes de ubicarse"
            }
        }

        it("no se puede capturar una línea ya ubicada") {
            val ubicada = lineaCapturada(100).locate(Quantity.of(100), estante).getOrThrow()
            val resultado = ubicada.capture(Quantity.of(100), BoxQuantity.of(2), operador)

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            error.error.shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("no se puede anular una línea con mercancía ubicada (R-57/R-68)") {
            val ubicada = lineaCapturada(100).locate(Quantity.of(100), estante).getOrThrow()
            val resultado = ubicada.cancel("ya no se necesita")

            val error = resultado.shouldBeInstanceOf<dev.axzy.stock.domain.common.Either.Left<*>>()
            withClue("Con efecto en inventario no se anula: se compensa con un movimiento") {
                error.error.shouldBeInstanceOf<DomainError.CannotDeleteWithInventoryEffect>()
            }
        }

        it("solo se puede asignar una línea pendiente") {
            lineaNueva().assign().isRight shouldBe true
            val resultado = lineaCapturada(100).assign()
            resultado.isLeft shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("estados: consultas derivadas en lugar de comparaciones de strings") {

        it("DISCREPANCY no permite ubicar y bloquea el cierre") {
            val estado = InvoiceLineStatus.DISCREPANCY
            estado.allowsLocating shouldBe false
            estado.blocksInvoiceClosing shouldBe true
        }

        it("LOCATED permite ubicar más pero no bloquea el cierre") {
            val estado = InvoiceLineStatus.LOCATED
            estado.allowsLocating shouldBe true
            estado.blocksInvoiceClosing shouldBe false
        }

        it("CANCELLED no permite nada") {
            val estado = InvoiceLineStatus.CANCELLED
            estado.allowsLocating shouldBe false
            estado.blocksInvoiceClosing shouldBe false
        }

        it("todo estado tiene una respuesta definida (el `when` es exhaustivo)") {
            InvoiceLineStatus.entries.forEach { estado ->
                withClue("El estado ${estado.name} debe tener ambas consultas definidas") {
                    // Si falta un caso en los `when` de arriba, esto no compila.
                    estado.allowsLocating
                    estado.blocksInvoiceClosing
                }
            }
        }
    }
})
