package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.error.BadRequestException
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PAGINACIÓN — pruebas puras (§1.4)
 *
 * Los bordes de `page`, `size` y `sort` se prueban aquí, sin base de datos y en
 * milisegundos, porque es donde están los errores que importan: recortar en
 * silencio el tamaño, aceptar un campo de orden que no existe o devolver una
 * página que no toca.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class PaginationTest : DescribeSpec({

    val fields = setOf("productCode", "name", "createdAt")

    fun analyze(page: String? = null, size: String? = null, sort: String? = null) =
        PageRequest.analyze(page, size, sort, fields, "productCode")

    describe("valores por defecto") {

        it("usa page 0 y size 50 cuando no se piden") {
            val page = analyze()

            page.page shouldBe 0
            page.size shouldBe 50
            page.fieldOrder shouldBe "productCode"
            page.ascending shouldBe true
        }

        it("sin orden explícito usa el campo por defecto ascendente") {
            analyze(sort = "").fieldOrder shouldBe "productCode"
        }

        it("calcula el desplazamiento que se pasa al OFFSET") {
            withClue("page=2 con size=50 salta 100 filas EN LA BASE, no en memoria") {
                analyze(page = "2").offset shouldBe 100L
            }
        }
    }

    describe("el sobre de paginación") {

        it("calcula el total de páginas con el total real, no con el tamaño de la página") {
            // 1.398 productos con páginas de 50 son 28 páginas: es el ejemplo del
            // contrato y el caso real del catálogo del cliente.
            Page(items = List(50) { it }, page = 0, size = 50, totalItems = 1398).totalPages shouldBe 28
        }

        it("una lista vacía tiene cero páginas, no una") {
            Page(items = emptyList<Int>(), page = 0, size = 50, totalItems = 0).totalPages shouldBe 0
        }

        it("el tamaño de la página no cambia el total de elementos") {
            Page(items = listOf(1, 2), page = 1, size = 2, totalItems = 3).totalItems shouldBe 3L
        }
    }

    describe("se rechaza lo que no encaja, no se recorta en silencio") {

        it("rechaza un size mayor que el máximo") {
            val error = runCatching { analyze(size = "201") }.exceptionOrNull()

            (error is BadRequestException) shouldBe true
            val badRequest = error as BadRequestException
            badRequest.code shouldBe "INVALID_PAGINATION"
            withClue("El mensaje debe explicar por qué no se recorta") {
                badRequest.message shouldContain "recorta"
            }
        }

        it("acepta justo el tamaño máximo") {
            analyze(size = "200").size shouldBe 200
        }

        it("rechaza size 0: no hay página posible") {
            (runCatching { analyze(size = "0") }.exceptionOrNull() as BadRequestException)
                .code shouldBe "INVALID_PAGINATION"
        }

        it("rechaza un page negativo") {
            (runCatching { analyze(page = "-1") }.exceptionOrNull() as BadRequestException)
                .code shouldBe "INVALID_PAGINATION"
        }

        it("rechaza un page que no es un número") {
            (runCatching { analyze(page = "primera") }.exceptionOrNull() as BadRequestException)
                .details?.get("page") shouldBe "primera"
        }

        it("rechaza un campo de orden no admitido y dice cuáles sí lo están") {
            val error = runCatching { analyze(sort = "stock,asc") }.exceptionOrNull() as BadRequestException

            error.code shouldBe "INVALID_SORT"
            withClue("El mensaje debe listar los campos admitidos: adivinar no es una opción") {
                error.message shouldContain "createdAt"
            }
        }

        it("rechaza una dirección de orden que no es asc ni desc") {
            (runCatching { analyze(sort = "name,arriba") }.exceptionOrNull() as BadRequestException)
                .code shouldBe "INVALID_SORT"
        }

        it("acepta la dirección en mayúsculas y sin espacios") {
            analyze(sort = " name , DESC ").let { page ->
                page.fieldOrder shouldBe "name"
                page.ascending shouldBe false
            }
        }

        it("acepta el campo sin dirección y asume ascendente") {
            analyze(sort = "createdAt").let { page ->
                page.fieldOrder shouldBe "createdAt"
                page.ascending shouldBe true
            }
        }
    }
})
