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

    describe("the product code is not changed") {

        it("a PUT that returns the same code is accepted: it is the client's round trip") {
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

        it("changing the code is rejected even if the rest is valid") {
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

    describe("the logical deletion of a product") {

        it("deactivating an active product works and keeps its history") {
            product().deactivate().getOrNull()!!.active shouldBe false
        }

        it("deactivating twice is rejected: it would erase the reason of the first deactivation") {
            product(active = false).deactivate().errorOrNull()
                .shouldBeInstanceOf<DomainError.ProductAlreadyInactive>()
        }
    }

    describe("the receiving location is protected") {

        val receiving = Location(
            id = 2, warehouseId = 1, hall = "RECEIVING", code = "RECEIVING", position = "RECEIVING",
            kind = LocationKind.RECEIVING,
        )

        it("cannot be deactivated") {
            receiving.deactivate().errorOrNull()
                .shouldBeInstanceOf<DomainError.ReceivingLocationProtected>()
        }

        it("nor by a PUT that leaves it inactive") {
            val result = receiving.withUpdated(
                hall = LocationPart.of("RECEIVING", "hall").getOrNull()!!,
                code = LocationPart.of("RECEIVING", "code").getOrNull()!!,
                position = LocationPart.of("RECEIVING", "position").getOrNull()!!,
                levels = 1,
                active = false,
            )

            result.errorOrNull().shouldBeInstanceOf<DomainError.ReceivingLocationProtected>()
        }

        it("a shelf can be deactivated") {
            val shelf = receiving.copy(id = 3, hall = "04", code = "B", position = "C", kind = LocationKind.SHELF)
            shelf.deactivate().getOrNull()!!.active shouldBe false
        }
    }

    describe("the visible name is composed by the server") {

        it("a shelf is shown as hall-code-position") {
            Location(3, 1, "04", "B", "C").displayName shouldBe "04-B-C"
        }

        it("receiving is shown with its code, not repeated three times") {
            Location(2, 1, "RECEIVING", "RECEIVING", "RECEIVING", kind = LocationKind.RECEIVING)
                .displayName shouldBe "RECEIVING"
        }

        it("the inherited balance is shown as UNASSIGNED") {
            Location(4, 1, "SIN", "UBICAR", "X", kind = LocationKind.UNASSIGNED)
                .displayName shouldBe "UNASSIGNED"
        }
    }

    describe("the parts of a location are normalised") {

        it("they are written in uppercase, which is how they are printed on the shelves") {
            LocationPart.of("b", "code").getOrNull()!!.value shouldBe "B"
        }

        it("a character that is not in the alphabet is rejected") {
            LocationPart.of("B-1", "code").errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidLocationPart>()
        }

        it("an empty part is rejected") {
            LocationPart.of("   ", "hall").errorOrNull()
                .shouldBeInstanceOf<DomainError.InvalidLocationPart>()
        }

        it("UNASSIGNED cannot be declared through the API: it comes from the migration") {
            LocationKind.parse("UNASSIGNED").isFailure shouldBe true
        }
    }

    describe("only one default warehouse") {

        it("marking another leaves the previous one without the mark") {
            val first = Warehouse(1, "MAIN", "Bodega principal", isDefault = true)
            val second = Warehouse(2, "NORTE", "Bodega norte")

            first.withDefault(false).isDefault shouldBe false
            second.withDefault(true).isDefault shouldBe true
        }
    }
})
