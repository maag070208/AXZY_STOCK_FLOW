package dev.axzy.stock.application.inventory

import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * El nombre visible de una ubicación es **la clave con la que el cliente la
 * identifica** en todo el flujo de inventario (`04-B-C`, `RECEIVING`, `UNASSIGNED`),
 * y la regla que lo compone vive en el dominio ([Location.displayName]).
 *
 * Esta prueba es la que impide que las dos se separen: para cada tipo de ubicación,
 * `LocationRef.parse(location.displayName)` tiene que devolver exactamente la
 * referencia que la describe. Si alguien cambia el formato del nombre visible y no
 * el intérprete —o al revés—, falla aquí y no en el almacén.
 */
class LocationRefTest {

    private fun assertRoundTrip(hall: String, code: String, position: String, kind: LocationKind) {
        val location = Location(id = 1, warehouseId = 1, hall = hall, code = code, position = position, kind = kind)
        val ref = LocationRef.parse(location.displayName)

        assertThat(ref).describedAs("ida y vuelta de ${location.displayName}").isNotNull
        assertThat(ref!!.displayName).isEqualTo(location.displayName)
    }

    @Test
    fun `a shelf's visible name is the same reference again`() {
        assertRoundTrip("04", "B", "C", LocationKind.SHELF)
        assertThat(LocationRef.parse("04-B-C")).isEqualTo(LocationRef.Shelf("04", "B", "C"))
    }

    @Test
    fun `the receiving and the unassigned balance are their reference again`() {
        assertRoundTrip("RECEIVING", "RECEIVING", "RECEIVING", LocationKind.RECEIVING)
        assertRoundTrip("UNASSIGNED", "UNASSIGNED", "UNASSIGNED", LocationKind.UNASSIGNED)

        assertThat(LocationRef.parse("RECEIVING")).isEqualTo(LocationRef.Receiving)
        assertThat(LocationRef.parse("UNASSIGNED")).isEqualTo(LocationRef.Unassigned)
        // El nombre de la columna y el visible son el mismo texto (V16): no hay un segundo
        // nombre que rechazar, y la referencia se reconoce por cualquiera de los dos.
    }

    @Test
    fun `lowercase is accepted because the client writes as it wants`() {
        assertThat(LocationRef.parse(" 04-b-c ")).isEqualTo(LocationRef.Shelf("04", "B", "C"))
        assertThat(LocationRef.parse("receiving")).isEqualTo(LocationRef.Receiving)
    }

    @Test
    fun `a string that cannot be a location is rejected instead of guessed`() {
        // El alfabeto de cada parte excluye el guion: con dos o cuatro partes no hay
        // fila que pueda corresponder, y devolver una cualquiera movería stock en el
        // estante equivocado.
        assertThat(LocationRef.parse("")).isNull()
        assertThat(LocationRef.parse("04-B")).isNull()
        assertThat(LocationRef.parse("04-B-C-D")).isNull()
        assertThat(LocationRef.parse("04--C")).isNull()
    }
}
