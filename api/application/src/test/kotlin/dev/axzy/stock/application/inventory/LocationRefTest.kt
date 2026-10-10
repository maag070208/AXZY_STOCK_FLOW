package dev.axzy.stock.application.inventory

import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The visible name of a location is **the key the client uses to identify it**
 * throughout the inventory flow (`04-B-C`, `RECEIVING`, `UNASSIGNED`),
 * and the rule that composes it lives in the domain ([Location.displayName]).
 *
 * This test is what prevents the two from separating: for each location type,
 * `LocationRef.parse(location.displayName)` has to return exactly the
 * reference that describes it. If someone changes the visible name's format and not the
 * parser —or the reverse—, it fails here and not in the warehouse.
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
        // The column name and the visible one are the same text (V16): there is no second
        // name to reject, and the reference is recognized by either of the two.
    }

    @Test
    fun `lowercase is accepted because the client writes as it wants`() {
        assertThat(LocationRef.parse(" 04-b-c ")).isEqualTo(LocationRef.Shelf("04", "B", "C"))
        assertThat(LocationRef.parse("receiving")).isEqualTo(LocationRef.Receiving)
    }

    @Test
    fun `a string that cannot be a location is rejected instead of guessed`() {
        // Each part's alphabet excludes the hyphen: with two or four parts there is no
        // row that can match, and returning any one would move stock in the
        // estante equivocado.
        assertThat(LocationRef.parse("")).isNull()
        assertThat(LocationRef.parse("04-B")).isNull()
        assertThat(LocationRef.parse("04-B-C-D")).isNull()
        assertThat(LocationRef.parse("04--C")).isNull()
    }
}
