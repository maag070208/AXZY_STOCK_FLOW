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
 * The audit log query (M08).
 *
 * The write log was already wired in `PersistenceConfig` since phase 3;
 * only the reading comes in here, which also writes its own access record.
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
