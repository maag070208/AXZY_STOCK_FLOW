package dev.axzy.stock.adapters.persistence.catalog

import dev.axzy.stock.application.catalog.UnitsOfMeasure
import dev.axzy.stock.domain.catalog.UnitOfMeasure
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * El catálogo cerrado de unidades de medida.
 *
 * Es cerrado a propósito: un campo de texto libre es lo que en el legacy producía
 * `KG`, `Kg` y `KGS` como tres unidades distintas del mismo producto.
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
