package dev.axzy.stock.adapters.persistence.labeling

import dev.axzy.stock.application.labeling.LabelFieldSpec
import dev.axzy.stock.application.labeling.LabelTemplateDetail
import dev.axzy.stock.application.labeling.LabelTemplatePatch
import dev.axzy.stock.application.labeling.LabelTemplateSummary
import dev.axzy.stock.application.labeling.LabelTemplates
import dev.axzy.stock.application.labeling.NewLabelTemplate
import dev.axzy.stock.application.labeling.NewPrintJob
import dev.axzy.stock.application.labeling.PrintJobView
import dev.axzy.stock.application.labeling.PrintJobs
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.labeling.LabelField
import dev.axzy.stock.domain.labeling.LabelTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Las etiquetas y su cola sobre PostgreSQL (docs/api/etiquetas.md).
 *
 * El `field_schema` y el `payload` son JSONB y se guardan **tal como llegan**, con el
 * serializador del proyecto (Jackson 3). El esquema de campos se convierte a
 * [LabelField] al leer para que el dominio pueda resolver la plantilla con la misma
 * función que usa la impresión.
 */
class JdbcLabels(private val jdbc: JdbcClient, private val json: JsonMapper) : LabelTemplates, PrintJobs {

    // ── Plantillas ──────────────────────────────────────────────────────────

    override fun list(onlyActive: Boolean): List<LabelTemplateSummary> {
        val clause = if (onlyActive) "WHERE is_active" else ""
        return query(
            """
            SELECT id, code, name, description, size, is_active, field_schema
            FROM label_templates $clause ORDER BY code
            """.trimIndent(),
            emptyMap(),
        ) { rs, _ -> rs.toSummary() }
    }

    override fun findTemplate(id: Long): LabelTemplateDetail? =
        query("$TEMPLATE_COLUMNS WHERE id = :id", mapOf("id" to id)) { rs, _ ->
            LabelTemplateDetail(
                summary = rs.toSummary(),
                zpl = rs.getString("zpl"),
                fields = parseFields(rs.getString("field_schema")),
            )
        }.firstOrNull()

    override fun findByCode(code: String): LabelTemplate? =
        query("$TEMPLATE_COLUMNS WHERE code = :code", mapOf("code" to code)) { rs, _ ->
            LabelTemplate(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                zpl = rs.getString("zpl"),
                size = rs.getString("size"),
                fields = parseFields(rs.getString("field_schema")),
                isActive = rs.getBoolean("is_active"),
            )
        }.firstOrNull()

    override fun codeExists(code: String): Boolean =
        query("SELECT 1 FROM label_templates WHERE code = :code", mapOf("code" to code)) { _, _ -> 1 }.isNotEmpty()

    override fun insert(new: NewLabelTemplate): Long =
        query(
            """
            INSERT INTO label_templates (code, name, description, zpl, size, field_schema, is_active)
            VALUES (:code, :name, :description, :zpl, :size, CAST(:schema AS jsonb), :active)
            RETURNING id
            """.trimIndent(),
            mapOf(
                "code" to new.code,
                "name" to new.name,
                "description" to new.description,
                "zpl" to new.zpl,
                "size" to new.size,
                "schema" to new.fieldSchema?.let { writeFields(it) },
                "active" to (new.isActive ?: true),
            ),
        ) { rs, _ -> rs.getLong(1) }.single()

    /**
     * La edición es **parcial**: solo se escribe lo que vino.
     *
     * El `SET` se compone con los campos presentes en lugar de escribir `null` en los
     * ausentes: el contrato dice que lo que no se envía queda como estaba, y un
     * `UPDATE` que escribe todos los campos obliga al cliente a mandar la plantilla
     * entera para cambiar una descripción.
     */
    override fun update(id: Long, patch: LabelTemplatePatch) {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>("id" to id)

        patch.code?.trim()?.takeIf { it.isNotBlank() }?.let { sets += "code = :code"; params["code"] = it }
        patch.name?.trim()?.takeIf { it.isNotBlank() }?.let { sets += "name = :name"; params["name"] = it }
        patch.description?.let { sets += "description = :description"; params["description"] = it }
        patch.zpl?.let { sets += "zpl = :zpl"; params["zpl"] = it }
        patch.size?.let { sets += "size = :size"; params["size"] = it }
        patch.isActive?.let { sets += "is_active = :active"; params["active"] = it }
        patch.fieldSchema?.let {
            sets += "field_schema = CAST(:schema AS jsonb)"
            params["schema"] = writeFields(it)
        }
        if (sets.isEmpty()) return

        sets += "updated_at = now()"
        update("UPDATE label_templates SET ${sets.joinToString(", ")} WHERE id = :id", params)
    }

    // ── La cola ─────────────────────────────────────────────────────────────

    override fun insert(job: NewPrintJob): Long =
        query(
            """
            INSERT INTO print_jobs (label_template_id, payload, copies, status, created_by, reprinted_from_id)
            VALUES (:template, CAST(:payload AS jsonb), :copies, 'PENDING', :createdBy, :reprintOf)
            RETURNING id
            """.trimIndent(),
            mapOf(
                "template" to job.templateId,
                "payload" to json.writeValueAsString(job.payload),
                "copies" to job.copies,
                "createdBy" to job.createdBy,
                "reprintOf" to job.reprintedFromId,
            ),
        ) { rs, _ -> rs.getLong(1) }.single()

    override fun findJob(id: Long): PrintJobView? =
        query("$JOB_COLUMNS $JOB_FROM WHERE j.id = :id", mapOf("id" to id)) { rs, _ -> rs.toJob() }.firstOrNull()

    override fun payloadOf(jobId: Long): Map<String, String>? =
        query("SELECT payload::text AS payload FROM print_jobs WHERE id = :id", mapOf("id" to jobId)) { rs, _ ->
            rs.getString("payload")
        }.firstOrNull()?.let { readPayload(it) }

    override fun exists(id: Long): Boolean =
        query("SELECT 1 FROM print_jobs WHERE id = :id", mapOf("id" to id)) { _, _ -> 1 }.isNotEmpty()

    override fun page(status: String?, request: PageRequest): Page<PrintJobView> {
        val where = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()
        status?.let {
            where += "j.status = CAST(:status AS print_status)"
            params["status"] = it
        }
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) $JOB_FROM $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$JOB_COLUMNS $JOB_FROM $clause ORDER BY j.id DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toJob() }
        return Page(items, request.page, request.size, total)
    }

    /**
     * El paso a `PRINTED`, **solo desde la confirmación de la impresora**.
     *
     * La condición viaja en el `WHERE` (`status <> 'PRINTED'`): si el trabajo ya estaba
     * impreso no se toca, y el número de filas afectadas es lo que el caso de uso usa
     * para responder `LABEL_ALREADY_PRINTED` —una etiqueta no se imprime dos veces por
     * un reintento de la impresora—.
     */
    override fun markPrinted(id: Long, at: Instant): Int =
        update(
            """
            UPDATE print_jobs SET status = 'PRINTED', printed_at = CAST(:at AS timestamptz),
                   attempts = attempts + 1, last_error = NULL, updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint) AND status <> 'PRINTED'
            """.trimIndent(),
            mapOf("at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC), "id" to id),
        )

    override fun markFailed(id: Long, error: String, at: Instant): Int =
        update(
            """
            UPDATE print_jobs SET status = 'FAILED', attempts = attempts + 1, last_error = CAST(:error AS text),
                   updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint) AND status <> 'PRINTED'
            """.trimIndent(),
            mapOf(
                "error" to error,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to id,
            ),
        )

    // ── Mapeo ───────────────────────────────────────────────────────────────

    private fun ResultSet.toSummary() = LabelTemplateSummary(
        id = getLong("id"),
        code = getString("code"),
        name = getString("name"),
        description = getString("description"),
        size = getString("size"),
        isActive = getBoolean("is_active"),
        fieldSchema = parseSchema(getString("field_schema")),
    )

    private fun ResultSet.toJob() = PrintJobView(
        id = getLong("id"),
        templateId = getLong("label_template_id"),
        templateCode = getString("template_code"),
        copies = getInt("copies"),
        status = getString("status"),
        attempts = getInt("attempts"),
        lastError = getString("last_error"),
        printedAt = getObject("printed_at", OffsetDateTime::class.java)?.toInstant(),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        reprintedFromId = getLong("reprinted_from_id").takeUnless { wasNull() },
    )

    /** El esquema llega como `{ "campo": { "type": …, "label": …, "required": … } }`. */
    @Suppress("UNCHECKED_CAST")
    private fun parseSchema(raw: String?): Map<String, LabelFieldSpec> {
        if (raw.isNullOrBlank()) return emptyMap()
        val parsed = json.readValue(raw, Map::class.java) as Map<String, Any?>
        return parsed.mapValues { (_, value) ->
            val spec = value as? Map<String, Any?> ?: emptyMap()
            LabelFieldSpec(
                type = spec["type"] as? String ?: "string",
                label = spec["label"] as? String ?: "",
                required = spec["required"] as? Boolean ?: false,
            )
        }
    }

    private fun parseFields(raw: String?): List<LabelField> =
        parseSchema(raw).map { (name, spec) ->
            LabelField(name = name, label = spec.label.ifBlank { name }, type = spec.type, required = spec.required)
        }

    private fun writeFields(fields: Map<String, LabelFieldSpec>): String = json.writeValueAsString(
        fields.mapValues { (_, spec) ->
            mapOf("type" to spec.type, "label" to spec.label, "required" to spec.required)
        },
    )

    @Suppress("UNCHECKED_CAST")
    private fun readPayload(raw: String): Map<String, String> {
        val parsed = json.readValue(raw, Map::class.java) as Map<String, Any?>
        return parsed.mapValues { (_, value) -> value?.toString() ?: "" }
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun update(sql: String, params: Map<String, Any?>): Int {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.update()
    }

    private companion object {
        const val TEMPLATE_COLUMNS =
            "SELECT id, code, name, description, zpl, size, is_active, field_schema FROM label_templates"

        val JOB_COLUMNS =
            """
            SELECT j.id, j.label_template_id, t.code AS template_code, j.copies, j.status::text AS status,
                   j.attempts, j.last_error, j.printed_at, j.created_at, j.reprinted_from_id
            """.trimIndent()

        const val JOB_FROM = "FROM print_jobs j JOIN label_templates t ON t.id = j.label_template_id"
    }
}
