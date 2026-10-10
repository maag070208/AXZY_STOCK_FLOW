package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.labeling.JdbcLabels
import dev.axzy.stock.adapters.persistence.service.JdbcServiceKeys
import dev.axzy.stock.adapters.web.labeling.LabelController
import dev.axzy.stock.adapters.web.labeling.LabelTemplateController
import dev.axzy.stock.adapters.web.labeling.PrintJobController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.labeling.ConfirmPrintJob
import dev.axzy.stock.application.labeling.CreateLabelTemplate
import dev.axzy.stock.application.labeling.GetLabelTemplate
import dev.axzy.stock.application.labeling.GetPrintJob
import dev.axzy.stock.application.labeling.LabelTemplates
import dev.axzy.stock.application.labeling.ListLabelTemplates
import dev.axzy.stock.application.labeling.ListPrintJobs
import dev.axzy.stock.application.labeling.PreviewLabel
import dev.axzy.stock.application.labeling.PrintJobs
import dev.axzy.stock.application.labeling.PrintLabels
import dev.axzy.stock.application.labeling.ReprintLabel
import dev.axzy.stock.application.labeling.UpdateLabelTemplate
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import tools.jackson.databind.json.JsonMapper

/**
 * Etiquetas (M06).
 *
 * The same adapter implements the two ports —templates and print queue—
 * because they share the job table: separating them would mean two connections to read a
 * job together with its template's code.
 */
@Configuration(proxyBeanMethods = false)
class LabelConfig {

    @Bean
    fun labels(jdbc: JdbcClient, json: JsonMapper): JdbcLabels = JdbcLabels(jdbc, json)

    @Bean
    fun listLabelTemplates(labels: JdbcLabels, access: AccessDecider) =
        ListLabelTemplates(labels as LabelTemplates, access)

    @Bean
    fun getLabelTemplate(labels: JdbcLabels, access: AccessDecider) =
        GetLabelTemplate(labels as LabelTemplates, access)

    @Bean
    fun createLabelTemplate(
        labels: JdbcLabels,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateLabelTemplate(labels as LabelTemplates, audit, auditEvents, access, tx)

    @Bean
    fun updateLabelTemplate(
        labels: JdbcLabels,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = UpdateLabelTemplate(labels as LabelTemplates, audit, auditEvents, access, tx)

    @Bean
    fun previewLabel(labels: JdbcLabels, access: AccessDecider) =
        PreviewLabel(labels as LabelTemplates, access)

    @Bean
    fun printLabels(
        labels: JdbcLabels,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = PrintLabels(labels as LabelTemplates, labels as PrintJobs, audit, auditEvents, access, tx)

    @Bean
    fun listPrintJobs(labels: JdbcLabels, access: AccessDecider) =
        ListPrintJobs(labels as PrintJobs, access)

    @Bean
    fun getPrintJob(labels: JdbcLabels, access: AccessDecider) =
        GetPrintJob(labels as PrintJobs, access)

    @Bean
    fun reprintLabel(
        labels: JdbcLabels,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = ReprintLabel(labels as PrintJobs, audit, auditEvents, access, tx)

    @Bean
    fun serviceKeys(jdbc: JdbcClient): JdbcServiceKeys = JdbcServiceKeys(jdbc)

    /**
     * The printer's confirmation: **the only place where a label becomes
     * printed**. It does not go through the access engine because there is no person or permission
     * to decide; the credential is the authorization.
     */
    @Bean
    fun confirmPrintJob(
        labels: JdbcLabels,
        keys: JdbcServiceKeys,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        tx: TransactionRunner,
        clock: Clock,
    ) = ConfirmPrintJob(labels as PrintJobs, keys, audit, auditEvents, tx, clock)

    @Bean
    fun printJobController(confirmPrintJob: ConfirmPrintJob) = PrintJobController(confirmPrintJob)

    @Bean
    fun labelTemplateController(
        listTemplates: ListLabelTemplates,
        getTemplate: GetLabelTemplate,
        createTemplate: CreateLabelTemplate,
        updateTemplate: UpdateLabelTemplate,
    ) = LabelTemplateController(listTemplates, getTemplate, createTemplate, updateTemplate)

    @Bean
    fun labelController(
        previewLabel: PreviewLabel,
        printLabels: PrintLabels,
        listJobs: ListPrintJobs,
        getJob: GetPrintJob,
        reprintLabel: ReprintLabel,
    ) = LabelController(previewLabel, printLabels, listJobs, getJob, reprintLabel)
}
