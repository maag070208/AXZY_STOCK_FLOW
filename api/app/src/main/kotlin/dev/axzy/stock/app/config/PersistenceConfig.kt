package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.audit.IndependentAccessDenialLog
import dev.axzy.stock.adapters.persistence.audit.JdbcAuditRecorder
import dev.axzy.stock.adapters.persistence.catalog.JdbcLocations
import dev.axzy.stock.adapters.persistence.catalog.JdbcProducts
import dev.axzy.stock.adapters.persistence.catalog.JdbcUnitsOfMeasure
import dev.axzy.stock.adapters.persistence.catalog.JdbcWarehouses
import dev.axzy.stock.adapters.persistence.health.JdbcDatabaseProbe
import dev.axzy.stock.adapters.persistence.identity.JdbcAccessCatalogStore
import dev.axzy.stock.adapters.persistence.identity.JdbcLoginAttempts
import dev.axzy.stock.adapters.persistence.identity.JdbcRefreshTokens
import dev.axzy.stock.adapters.persistence.identity.JdbcUserAccounts
import dev.axzy.stock.adapters.persistence.shared.JdbcTransactionRunner
import dev.axzy.stock.application.audit.AccessDenialLog
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.catalog.Products
import dev.axzy.stock.application.catalog.UnitsOfMeasure
import dev.axzy.stock.application.catalog.Warehouses
import dev.axzy.stock.application.health.DatabaseProbe
import dev.axzy.stock.application.identity.AccessCatalogStore
import dev.axzy.stock.application.identity.LoginAttempts
import dev.axzy.stock.application.identity.RefreshTokens
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper

/** Los puertos de salida, implementados sobre PostgreSQL. */
@Configuration(proxyBeanMethods = false)
class PersistenceConfig {

    @Bean
    fun transactionRunner(transactionManager: PlatformTransactionManager): TransactionRunner =
        JdbcTransactionRunner(TransactionTemplate(transactionManager))

    @Bean
    fun auditRecorder(jdbc: JdbcClient, json: JsonMapper): AuditRecorder = JdbcAuditRecorder(jdbc, json)

    /** Las denegaciones se escriben en una transacción propia: sobreviven al rollback de quien las provocó. */
    @Bean
    fun accessDenialLog(recorder: AuditRecorder, transactionManager: PlatformTransactionManager): AccessDenialLog =
        IndependentAccessDenialLog(
            recorder,
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            },
        )

    @Bean
    fun databaseProbe(jdbc: JdbcClient): DatabaseProbe = JdbcDatabaseProbe(jdbc)

    @Bean
    fun userAccounts(jdbc: JdbcClient): UserAccounts = JdbcUserAccounts(jdbc)

    @Bean
    fun loginAttempts(jdbc: JdbcClient): LoginAttempts = JdbcLoginAttempts(jdbc)

    @Bean
    fun refreshTokens(jdbc: JdbcClient): RefreshTokens = JdbcRefreshTokens(jdbc)

    @Bean
    fun accessCatalogStore(jdbc: JdbcClient, json: JsonMapper): AccessCatalogStore = JdbcAccessCatalogStore(jdbc, json)

    // ── Catálogo (M01) ──────────────────────────────────────────────────────

    @Bean
    fun products(jdbc: JdbcClient, json: JsonMapper): Products = JdbcProducts(jdbc, json)

    @Bean
    fun locations(jdbc: JdbcClient): Locations = JdbcLocations(jdbc)

    @Bean
    fun warehouses(jdbc: JdbcClient): Warehouses = JdbcWarehouses(jdbc)

    @Bean
    fun unitsOfMeasure(jdbc: JdbcClient): UnitsOfMeasure = JdbcUnitsOfMeasure(jdbc)
}
