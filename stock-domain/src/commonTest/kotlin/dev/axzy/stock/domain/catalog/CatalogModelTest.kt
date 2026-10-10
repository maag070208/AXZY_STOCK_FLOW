package dev.axzy.stock.domain.catalog

import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.ProductCode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Las reglas del catálogo que no son de forma (docs/flujos/catalogo.md §3).
 *
 * Se prueban sin base de datos: si una operación es admisible lo decide el
 * dominio, y el esquema solo lo refuerza.
 */
class CatalogModelTest : DescribeSpec({

    val code = ProductCode.of("ABRAA1B")

    fun product(active: Boolean = true) = Product(
        id = 1,
        code = code,
        name = "ABRAZADERA DE ARRANQUE",
        description = null,
        unitMeasureId = 1,
        unitMeasure = "PZA",
        externalId = null,
        active = active,
    )

    describe("el código de producto no se cambia") {

        it("un PUT que devuelve el mismo código se acepta: es el ida y vuelta del cliente") {
            val updated = product().withUpdated(
                code = code,
                name = "OTRO NOMBRE",
                description = null,
                unitMeasureId = 1,
                unitMeasure = "PZA",
                active = true,
            )

            updated.getOrNull()!!.name shouldBe "OTRO NOMBRE"
        }

        it("cambiar el código se rechaza aunque el resto sea válido") {
            val result = product().withUpdated(
                code = ProductCode.of("ABRAA1C"),
                name = "ABRAZADERA DE ARRANQUE",
                description = null,
                unitMeasureId = 1,
                unitMeasure = "PZA",
                active = true,
            )

            val error = result.errorOrNull().shouldBeInstanceOf<DomainError.ProductCodeImmutable>()
            error.code shouldBe "PRODUCT_CODE_IMMUTABLE"
        }
    }

    describe("la baja lógica de un producto") {

        it("desactivar un producto activo funciona y conserva su historial") {
            product().deactivate().getOrNull()!!.active shouldBe false
        }

        it("desactivar dos veces se rechaza: borraría el motivo del primer borrado") {
            product(active = false).deactivate().errorOrNull()
                .shouldBeInstanceOf<DomainError.ProductAlreadyInactive>()
        }
    }

    describe("la ubicación de recepción está protegida") {

        val receiving = Location(
            id = 2, warehouseId = 1, hall = "RECEPCION", code = "RECEPCION", position = "RECEPCION",
            kind = LocationKind.RECEIVING,
        )

        it("no se puede desactivar") {
            receiving.deactivate().errorOrNull()
                .shouldBeInstanceOf<DomainError.ReceivingLocationProtected>()
        }

        it("tampoco por un PUT que la deje inactiva") {
            val result = receiving.withUpdated(
                hall = LocationPart.of("RECEPCION", "hall").getOrNull()!!,
                code = LocationPart.of("RECEPCION", "code").getOrNull()!!,
                position = LocationPart.of("RECEPCION", "position").getOrNull()!!,
                levels = 1,
                active = false,
            )

            result.errorOrNull().shouldBeInstanceOf<DomainError.ReceivingLocationProtected>()
        }

        it("un estante sí se desactiva") {
            val shelf = receiving.copy(id = 3, hall = "04", code = "B", position = "C", kind = LocationKind.SHELF)
            shelf.deactivate().getOrNull()!!.active shouldBe false
        }
    }

    describe("el nombre visible lo compone el servidor") {

        it("un estante se muestra como pasillo-localidad-posición") {
            Location(3, 1, "04", "B", "C").displayName shouldBe "04-B-C"
        }

        it("la recepción se muestra con su código, no repetida tres veces") {
            Location(2, 1, "RECEPCION", "RECEPCION", "RECEPCION", kind = LocationKind.RECEIVING)
                .displayName shouldBe "RECEPCION"
        }

        it("el saldo heredado se muestra como SIN-UBICAR") {
            Location(4, 1, "SIN", "UBICAR", "X", kind = LocationKind.UNASSIGNED)
                .displayName shouldBe "SIN-UBICAR"
        }
    }

    describe("las partes de una ubicación se normalizan") {

        it("se escriben en mayúsculas, que es como están impresas en los estantes") {
            LocationPart.of("b", "code").getOrNull()!!.value shouldBe "B"
        }

        it("un carácter que no está en el alfabeto se rechaza") {
            LocationPart.of("B-1", "code").errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidLocationPart>()
        }

        it("una parte vacía se rechaza") {
            LocationPart.of("   ", "hall").errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidLocationPart>()
        }

        it("UNASSIGNED no se puede declarar por API: nace de la migración") {
            LocationKind.parse("UNASSIGNED").isFailure shouldBe true
        }
    }

    describe("solo una bodega predeterminada") {

        it("marcar otra deja a la anterior sin la marca") {
            val first = Warehouse(1, "PRINCIPAL", "Bodega principal", isDefault = true)
            val second = Warehouse(2, "NORTE", "Bodega norte")

            first.withDefault(false).isDefault shouldBe false
            second.withDefault(true).isDefault shouldBe true
        }
    }
})
