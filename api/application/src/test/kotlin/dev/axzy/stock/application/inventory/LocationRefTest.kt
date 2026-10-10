package dev.axzy.stock.application.inventory

import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * El nombre visible de una ubicación es **la clave con la que el cliente la
 * identifica** en todo el flujo de inventario (`04-B-C`, `RECEPCION`, `SIN-UBICAR`),
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
    fun `el nombre visible de un estante vuelve a ser la misma referencia`() {
        assertRoundTrip("04", "B", "C", LocationKind.SHELF)
        assertThat(LocationRef.parse("04-B-C")).isEqualTo(LocationRef.Shelf("04", "B", "C"))
    }

    @Test
    fun `la recepcion y el saldo sin ubicar vuelven a ser su referencia`() {
        assertRoundTrip("RECEPCION", "RECEPCION", "RECEPCION", LocationKind.RECEIVING)
        assertRoundTrip("SINUBICAR", "SINUBICAR", "SINUBICAR", LocationKind.UNASSIGNED)

        assertThat(LocationRef.parse("RECEPCION")).isEqualTo(LocationRef.Receiving)
        assertThat(LocationRef.parse("SIN-UBICAR")).isEqualTo(LocationRef.Unassigned)
        // El nombre de las COLUMNAS (`SINUBICAR`) no es el del contrato: el cliente
        // escribe `SIN-UBICAR`, que es lo que compone el dominio. Aceptar los dos
        // sería dos nombres para la misma ubicación.
        assertThat(LocationRef.parse("SINUBICAR")).isNull()
    }

    @Test
    fun `se acepta en minusculas porque el cliente escribe como quiere`() {
        assertThat(LocationRef.parse(" 04-b-c ")).isEqualTo(LocationRef.Shelf("04", "B", "C"))
        assertThat(LocationRef.parse("recepcion")).isEqualTo(LocationRef.Receiving)
    }

    @Test
    fun `una cadena que no puede ser una ubicacion se rechaza en lugar de adivinar`() {
        // El alfabeto de cada parte excluye el guion: con dos o cuatro partes no hay
        // fila que pueda corresponder, y devolver una cualquiera movería stock en el
        // estante equivocado.
        assertThat(LocationRef.parse("")).isNull()
        assertThat(LocationRef.parse("04-B")).isNull()
        assertThat(LocationRef.parse("04-B-C-D")).isNull()
        assertThat(LocationRef.parse("04--C")).isNull()
    }
}
