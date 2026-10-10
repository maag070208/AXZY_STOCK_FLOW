package dev.axzy.stock.adapters.persistence.catalog

import dev.axzy.stock.application.catalog.UnitsOfMeasure
import dev.axzy.stock.domain.catalog.UnitOfMeasure
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The closed catalog of units of measure.
 *
 * It is closed on purpose: a free-text field is what in the legacy produced
 * `KG`, `Kg` and `KGS` as three different units of the same product.
 */
class JdbcUnitsOfMeasure(private val jdbc: JdbcClient) : UnitsOfMeasure {

    override fun all(): List<UnitOfMeasure> =
        jdbc.sql("SELECT id, code, name, allows_fraction FROM units_of_measure ORDER BY code")
            .query { rs, _ ->
                UnitOfMeasure(
                    id = rs.getLong("id"),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    allowsFraction = rs.getBoolean("allows_fraction"),
                )
            }
            .list()
}
