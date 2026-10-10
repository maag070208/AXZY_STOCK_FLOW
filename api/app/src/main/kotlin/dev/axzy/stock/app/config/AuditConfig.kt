package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.audit.JdbcAuditQueries
import dev.axzy.stock.adapters.web.audit.AuditController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditQueries
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.audit.QueryAuditLog
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * La consulta de la bitácora (M08).
 *
 * El registro de escritura ya estaba cableado en `PersistenceConfig` desde la fase 3;
 * aquí solo entra la lectura, que además escribe su propio registro de acceso.
 */
@Configuration(proxyBeanMethods = false)
class AuditConfig {

    @Bean
    fun auditQueries(jdbc: JdbcClient): JdbcAuditQueries = JdbcAuditQueries(jdbc)

    @Bean
    fun queryAuditLog(
        queries: JdbcAuditQueries,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
    ) = QueryAuditLog(queries as AuditQueries, audit, auditEvents, access)

    @Bean
    fun auditController(queryAuditLog: QueryAuditLog) = AuditController(queryAuditLog)
}
