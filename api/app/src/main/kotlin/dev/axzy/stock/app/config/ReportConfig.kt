package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryQueries
import dev.axzy.stock.adapters.persistence.reports.JdbcReportQueries
import dev.axzy.stock.adapters.web.reports.ReportController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.reports.ExportKardex
import dev.axzy.stock.application.reports.InventoryReport
import dev.axzy.stock.application.reports.KardexReconciliation
import dev.axzy.stock.application.reports.MovementSummary
import dev.axzy.stock.application.reports.ReportQueries
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * Los reportes (docs/api/reportes.md), que pertenecen a M02.
 *
 * El reporte de reconciliación se cablea con el **reloj de la aplicación**: el
 * `checkedAt` que devuelve tiene que ser el mismo instante que usan la bitácora y las
 * políticas, no el del sistema operativo en cada consulta.
 */
@Configuration(proxyBeanMethods = false)
class ReportConfig {

    @Bean
    fun reportQueries(
        jdbc: JdbcClient,
        kardexPages: JdbcInventoryQueries,
        properties: StockProperties,
    ): ReportQueries = JdbcReportQueries(jdbc, kardexPages, properties.businessZone)

    @Bean
    fun inventoryReport(queries: ReportQueries, access: AccessDecider) = InventoryReport(queries, access)

    @Bean
    fun movementSummary(queries: ReportQueries, access: AccessDecider) = MovementSummary(queries, access)

    @Bean
    fun kardexReconciliation(queries: ReportQueries, access: AccessDecider, clock: Clock) =
        KardexReconciliation(queries, access, clock)

    @Bean
    fun exportKardex(queries: ReportQueries, access: AccessDecider) = ExportKardex(queries, access)

    @Bean
    fun reportController(
        inventoryReport: InventoryReport,
        movementSummary: MovementSummary,
        reconciliation: KardexReconciliation,
        exportKardex: ExportKardex,
    ) = ReportController(inventoryReport, movementSummary, reconciliation, exportKardex)
}
