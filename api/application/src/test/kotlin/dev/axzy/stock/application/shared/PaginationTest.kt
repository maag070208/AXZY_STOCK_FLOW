package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.common.Either
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Las reglas de paginación de docs/api/convenciones.md §1.4.
 *
 * El detalle que más importa: **`size` mayor de 200 se rechaza, no se recorta**.
 * Recortar en silencio hace que el cliente crea que tiene todos los datos.
 */
class PaginationTest {

    private val sorts = setOf("productCode", "name", "createdAt")
    private val default = SortSpec("productCode")

    private fun analyze(page: Int? = null, size: Int? = null, sort: String? = null): Outcome<PageRequest> =
        PageRequest.analyze(page, size, sort, sorts, default)

    private fun errorCode(outcome: Outcome<PageRequest>): String? = (outcome as? Either.Left)?.error?.code

    private fun value(outcome: Outcome<PageRequest>): PageRequest = (outcome as Either.Right).value

    @Test
    fun `sin parametros se usa la pagina cero con cincuenta filas`() {
        val request = value(analyze())

        assertThat(request.page).isZero()
        assertThat(request.size).isEqualTo(PageRequest.DEFAULT_SIZE)
        assertThat(request.offset).isZero()
        assertThat(request.sort).isEqualTo(default)
    }

    @Test
    fun `un tamano mayor de doscientas se rechaza en lugar de recortarse`() {
        assertThat(errorCode(analyze(size = 201))).isEqualTo("INVALID_PAGINATION")
        assertThat(errorCode(analyze(size = 0))).isEqualTo("INVALID_PAGINATION")
        assertThat(errorCode(analyze(size = -1))).isEqualTo("INVALID_PAGINATION")
        assertThat(value(analyze(size = 200)).size).isEqualTo(200)
    }

    @Test
    fun `una pagina negativa se rechaza`() {
        assertThat(errorCode(analyze(page = -1))).isEqualTo("INVALID_PAGINATION")
    }

    @Test
    fun `el desplazamiento sale de la pagina y el tamano`() {
        assertThat(value(analyze(page = 3, size = 50)).offset).isEqualTo(150)
    }

    @Test
    fun `un orden que no se admite se rechaza y no se ignora`() {
        assertThat(errorCode(analyze(sort = "password,asc"))).isEqualTo("INVALID_SORT")
        assertThat(errorCode(analyze(sort = "name,descendente"))).isEqualTo("INVALID_SORT")
        assertThat(errorCode(analyze(sort = "name,asc,extra"))).isEqualTo("INVALID_SORT")
    }

    @Test
    fun `el orden se lee como campo, direccion`() {
        assertThat(value(analyze(sort = "name,desc")).sort)
            .isEqualTo(SortSpec("name", SortDirection.DESC))
        // Sin dirección, ascendente: es lo que hace un cliente que solo manda el campo.
        assertThat(value(analyze(sort = "name")).sort).isEqualTo(SortSpec("name", SortDirection.ASC))
    }

    @Test
    fun `el total de paginas se calcula redondeando hacia arriba y nunca con items size`() {
        assertThat(Page(listOf("a"), page = 0, size = 50, totalItems = 1398).totalPages).isEqualTo(28)
        assertThat(Page(listOf("a"), page = 0, size = 50, totalItems = 0).totalPages).isZero()
        assertThat(Page(listOf("a"), page = 0, size = 50, totalItems = 51).totalPages).isEqualTo(2)
    }
}
