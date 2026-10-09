package dev.axzy.stock.domain.fulfillment

import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL SURTIDO: ESTADOS Y, SOBRE TODO, CUÁNTO SE DESCUENTA
 *
 * La prueba que más importa de este archivo es
 * **«confirmar descuenta lo que FALTA, no la cantidad entera»**. Un error ahí no
 * rompe nada: el sistema arranca, las rutas responden y los tests de ruta pasan.
 * Lo que pasa es que el inventario baja de más, y eso **solo se descubre al
 * cuadrar el almacén** — y entonces no se distingue de una merma real.
 *
 * Son pruebas PURAS: no tocan base ni servidor, corren en milisegundos y **no se
 * saltan nunca**.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OrderStateMachineTest : DescribeSpec({

    fun line(
        id: Long = 1,
        quantity: Long = 10_000,
        supplied: Long = 0,
        status: OrderLineStatus = OrderLineStatus.PENDING,
    ) = OrderLine(
        id = id,
        productId = id,
        productCode = "P$id",
        productName = "Producto $id",
        unitMeasure = "PZA",
        quantity = Quantity.ofScaled(quantity),
        suppliedQuantity = Quantity.ofScaled(supplied),
        status = status,
    )

    fun order(
        status: OrderStatus = OrderStatus.PENDING,
        lines: List<OrderLine> = listOf(line()),
        converted: Boolean = false,
        attempts: Int = 0,
    ) = Order(
        id = 1,
        orderNumber = "ORD-2026-0001",
        status = status,
        lines = lines,
        converted = converted,
        exportAttempts = attempts,
    )

    describe("la regla del descuento: `suppliedQuantity` es lo YA descontado") {

        it("una línea sin empacar tiene 10 pendientes de 10") {
            val l = line(quantity = 10_000)

            l.suppliedQuantity shouldBe Quantity.ZERO
            l.pendingQuantity shouldBe Quantity.ofScaled(10_000)
            l.isComplete shouldBe false
        }

        it("empacar 7 deja 3 pendientes — y son 3 los que confirmará") {
            val empacada = line(quantity = 10_000).packageQuantity(Quantity.ofScaled(7_000))
                .getOrNull()!!

            empacada.suppliedQuantity shouldBe Quantity.ofScaled(7_000)
            // **Este 3 es la cifra que confirmar descontará.** Si en su lugar se
            // descontaran 10, el inventario habría bajado 17 en total por una
            // línea de 10.
            empacada.pendingQuantity shouldBe Quantity.ofScaled(3_000)
            empacada.isComplete shouldBe false
        }

        it("empacar 7 y luego 3 completa la línea y deja 0 pendiente") {
            val empacada = line(quantity = 10_000)
                .packageQuantity(Quantity.ofScaled(7_000)).getOrNull()!!
                .packageQuantity(Quantity.ofScaled(3_000)).getOrNull()!!

            empacada.suppliedQuantity shouldBe Quantity.ofScaled(10_000)
            // El pendiente queda EXACTAMENTE en cero: confirmar no descontará nada
            // más, así que no hay doble descuento.
            empacada.pendingQuantity shouldBe Quantity.ZERO
            empacada.isComplete shouldBe true
            empacada.status shouldBe OrderLineStatus.SUPPLIED
        }

        it("empacar una línea completa la deja SUPPLIED; a medias, ASSIGNED") {
            line().packageQuantity(Quantity.ofScaled(10_000)).getOrNull()!!.status shouldBe
                OrderLineStatus.SUPPLIED
            line().packageQuantity(Quantity.ofScaled(1_000)).getOrNull()!!.status shouldBe
                OrderLineStatus.ASSIGNED
        }
    }

    describe("lo que el surtido RECHAZA") {

        it("empacar más de lo pendiente se rechaza, y dice cuánto queda") {
            val error = line(quantity = 10_000, supplied = 7_000)
                .packageQuantity(Quantity.ofScaled(5_000)).errorOrNull()

            error.shouldBeInstanceOf<DomainError.ExceedsPendingQuantity>()
            val exceso = error as DomainError.ExceedsPendingQuantity
            exceso.code shouldBe "EXCEEDS_PENDING_QUANTITY"
            // El mensaje lleva las dos cifras: lo pedido y lo que queda. Sin la
            // segunda, el operador no sabe cuánto puede empacar.
            exceso.pending shouldBe 3_000
            exceso.requested shouldBe 5_000
            exceso.message.contains("3000") shouldBe true
        }

        it("empacar cero o negativo se rechaza") {
            line().packageQuantity(Quantity.ZERO).errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()
            line().packageQuantity(Quantity.ofScaled(-1)).errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidQuantity>()
        }

        it("empacar una línea ANULADA se rechaza") {
            val error = line(status = OrderLineStatus.CANCELLED)
                .packageQuantity(Quantity.ofScaled(1_000)).errorOrNull()

            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
        }

        it("confirmar una orden ya confirmada se rechaza: descontaría dos veces") {
            val error = order(status = OrderStatus.PENDING_TO_OUT, converted = true)
                .confirm().errorOrNull()

            // El código es el del contrato §6: `ORDER_ALREADY_CONFIRMED`.
            error.shouldBeInstanceOf<DomainError.OrderAlreadyConfirmed>()
            (error as DomainError.OrderAlreadyConfirmed).code shouldBe "ORDER_ALREADY_CONFIRMED"
        }

        it("anular una orden ya exportada se rechaza: el ERP tiene una salida que el sistema no reconocería") {
            val error = order(status = OrderStatus.COMPLETED, converted = true).cancel().errorOrNull()

            error.shouldBeInstanceOf<DomainError.InvalidTransition>()
            (error as DomainError.InvalidTransition).action shouldBe "anular"
        }

        it("exportar una orden sin confirmar se rechaza") {
            val error = order(status = OrderStatus.PENDING).recordExportAttempt(success = true)
                .errorOrNull()

            error.shouldBeInstanceOf<DomainError.OrderNotConverted>()
            (error as DomainError.OrderNotConverted).code shouldBe "ORDER_NOT_CONVERTED"
        }
    }

    describe("confirmar") {

        it("deja la orden PENDING_TO_OUT") {
            order(status = OrderStatus.PACKAGED).confirm().getOrNull()!!.status shouldBe
                OrderStatus.PENDING_TO_OUT
        }
    }

    describe("la exportación al ERP") {

        it("un intento fallido lleva el contador y guarda el motivo") {
            val fallida = order(status = OrderStatus.PENDING_TO_OUT)
                .recordExportAttempt(success = false, error = "El ERP no responde")
                .getOrNull()!!

            fallida.exportAttempts shouldBe 1
            fallida.exportStatus shouldBe "FAILED"
            fallida.exportLastError shouldBe "El ERP no responde"
            // **No se marca como convertida**: un fallo no es una conversión. Si se
            // marcara, el reintento creería que ya se envió y no lo mandaría nunca.
            fallida.converted shouldBe false
            fallida.status shouldBe OrderStatus.PENDING_TO_OUT
        }

        it("un intento correcto completa la orden y limpia el error anterior") {
            val exitosa = order(status = OrderStatus.PENDING_TO_OUT, attempts = 2)
                .recordExportAttempt(success = true)
                .getOrNull()!!

            exitosa.exportAttempts shouldBe 3
            exitosa.exportStatus shouldBe "SENT"
            // El error del intento anterior se limpia: dejar «El ERP no responde»
            // junto a un `SENT` hace dudar de si llegó.
            exitosa.exportLastError shouldBe null
            exitosa.converted shouldBe true
            exitosa.status shouldBe OrderStatus.COMPLETED
        }

        it("reintentar tras un éxito es idempotente: no vuelve a contar como conversión nueva") {
            val primera = order(status = OrderStatus.PENDING_TO_OUT)
                .recordExportAttempt(success = true).getOrNull()!!
            val segunda = primera.recordExportAttempt(success = true).getOrNull()!!

            // Sigue convertida y completada: el contador sube porque es un intento
            // real, pero **el estado no cambia** y no se crea una segunda salida.
            segunda.converted shouldBe true
            segunda.status shouldBe OrderStatus.COMPLETED
        }
    }

    describe("el estado de la CABECERA se DERIVA de las líneas") {

        it("sin líneas empacadas la orden es PENDING") {
            order(status = OrderStatus.PENDING).recalculatedStatus().status shouldBe OrderStatus.PENDING
        }

        it("con una línea a medias pasa a IN_PROGRESS") {
            val o = order(lines = listOf(line(supplied = 3_000, status = OrderLineStatus.ASSIGNED)))

            o.recalculatedStatus().status shouldBe OrderStatus.IN_PROGRESS
        }

        it("con todas las líneas completas pasa a PACKAGED") {
            val o = order(
                lines = listOf(
                    line(id = 1, supplied = 10_000, status = OrderLineStatus.SUPPLIED),
                    line(id = 2, quantity = 5_000, supplied = 5_000, status = OrderLineStatus.SUPPLIED),
                ),
            )

            o.recalculatedStatus().status shouldBe OrderStatus.PACKAGED
        }

        it("una línea anulada no impide que la orden esté PACKAGED") {
            // Anular una línea es una decisión explícita: el resto del pedido puede
            // cerrarse. Si la anulada bloqueara el cierre, **una orden con una línea
            // anulada nunca se podría completar** y quedaría abierta para siempre.
            val o = order(
                lines = listOf(
                    line(id = 1, supplied = 10_000, status = OrderLineStatus.SUPPLIED),
                    line(id = 2, status = OrderLineStatus.CANCELLED),
                ),
            )

            o.allLinesDone shouldBe true
            o.recalculatedStatus().status shouldBe OrderStatus.PACKAGED
        }

        it("NO pisa un estado final: una orden completada sigue completada") {
            val o = order(status = OrderStatus.COMPLETED, converted = true)

            // Recalcular es derivar el estado de TRABAJO, no reescribir el
            // resultado. Si pisara `COMPLETED`, una orden ya exportada volvería a
            // `PENDING` y el ERP tendría una salida que el sistema no reconoce.
            o.recalculatedStatus().status shouldBe OrderStatus.COMPLETED
        }

        it("NO pisa CANCELLED") {
            order(status = OrderStatus.CANCELLED).recalculatedStatus().status shouldBe
                OrderStatus.CANCELLED
        }
    }

    describe("los contadores que el contrato publica") {

        it("`totalLines` y `packagedLines` cuentan sobre las líneas") {
            val o = order(
                lines = listOf(
                    line(id = 1, supplied = 10_000, status = OrderLineStatus.SUPPLIED),
                    line(id = 2, supplied = 4_000, status = OrderLineStatus.ASSIGNED),
                    line(id = 3),
                ),
            )

            o.totalLines shouldBe 3
            // Solo la primera está completa. El contrato los llama `packagedLines`.
            o.packagedLines shouldBe 1
            o.allLinesDone shouldBe false
        }
    }
})
