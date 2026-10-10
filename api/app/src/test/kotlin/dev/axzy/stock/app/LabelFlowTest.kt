package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Labels (M06) over real HTTP.
 *
 * L1, L3 and L5 of docs/flujos/etiquetas.md §5. **L2 and L4 need the printer's**
 * confirmation** (`PATCH /v1/print-jobs/{id}` with `X-Service-Key`), which is what is missing from the
 * module: here the exact opposite is checked —that no user route marks a
 * label as printed—, which is the half that is in place.
 */
class LabelFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun template(
        token: String,
        code: String = "receipt",
        zpl: String = "^XA^FD{{productCode}}^FS^XZ",
        schema: Map<String, Any?> = mapOf("productCode" to mapOf("type" to "string", "label" to "Código", "required" to true)),
        isActive: Boolean = true,
    ): Long {
        val response = post(
            "/v1/label-templates",
            mapOf(
                "code" to code,
                "name" to "Recibo de mercancía",
                "zpl" to zpl,
                "size" to "4x6",
                "fieldSchema" to schema,
                "isActive" to isActive,
            ),
            token,
        )
        assertThat(response.status).describedAs("alta de plantilla: ${response.raw}").isEqualTo(201)
        return response.at<Int>("id").toLong()
    }

    private fun preview(token: String, templateId: Long, data: Map<String, String>? = mapOf("productCode" to "ABRAA1B")) =
        post("/v1/labels/preview", mapOf("templateId" to templateId, "data" to data), token)

    private fun print(token: String, templateId: Long, data: List<Map<String, String>>? = null, copies: Int? = null) =
        post(
            "/v1/labels/print",
            buildMap<String, Any?> {
                put("templateId", templateId)
                put("copies", copies)
                put("data", data ?: listOf(mapOf("productCode" to "ABRAA1B")))
            },
            token,
        )

    // ── Plantillas ──────────────────────────────────────────────────────────

    @Test
    fun `the list does not bring the ZPL and the detail does`() {
        val token = manager()
        val id = template(token)

        // The listing is a plain ARRAY without a pagination envelope: a closed catalog.
        assertThat(get("/v1/label-templates", token).raw).startsWith("[")
        assertThat(get("/v1/label-templates", token).raw).doesNotContain("zpl")

        val detalle = get("/v1/label-templates/$id", token)
        assertThat(detalle.at<String>("code")).isEqualTo("receipt")
        assertThat(detalle.at<String>("zpl")).contains("^XA")
        assertThat(detalle.at<Map<String, Any?>>("fieldSchema")).containsKey("productCode")
    }

    @Test
    fun `a ZPL without a closing is not saved`() {
        val token = manager()

        val response = post(
            "/v1/label-templates",
            mapOf("code" to "rota", "name" to "Rota", "zpl" to "^XA^FD{{productCode}}^FS"),
            token,
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INVALID_ZPL")
    }

    @Test
    fun `the edit is partial and leaves what is not sent`() {
        val token = manager()
        val id = template(token)

        val response = put("/v1/label-templates/$id", mapOf("name" to "Otro nombre"), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("name")).isEqualTo("Otro nombre")
        // The ZPL and the code stay as they were.
        assertThat(response.at<String>("code")).isEqualTo("receipt")
        assertThat(response.at<String>("zpl")).contains("^XA")
    }

    // ── Preview ──────────────────────────────────────────────────────────────

    @Test
    fun `the preview resolves the ZPL and the server sets the size`() {
        val token = manager()
        val id = template(token, zpl = "^XA^FD{{productCode}}^FS^XZ")

        val response = preview(token, id)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("zpl")).isEqualTo("^XA^FDABRAA1B^FS^XZ")
        val preview = response.at<Map<String, Any?>>("preview")
        // 4x6 inches in millimeters, computed on the server.
        assertThat((preview["widthMm"] as Number).toDouble()).isEqualTo(101.6)
        assertThat((preview["heightMm"] as Number).toDouble()).isEqualTo(152.4)
        assertThat(preview["copies"]).isEqualTo(1)
    }

    @Test
    fun `a missing required field does not produce a half-made label`() {
        val token = manager()
        val id = template(token)

        val response = preview(token, id, data = emptyMap())

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("MISSING_LABEL_FIELD")
    }

    @Test
    fun `a control character in the data does not break the label`() {
        val token = manager()
        // ZPL uses `^` for its commands: a name with `^` would close the field too early
        // and the rest would be read as a printer command.
        val id = template(token, zpl = "^XA^FD{{nombre}}^FS^XZ", schema = mapOf("nombre" to mapOf("type" to "string")))

        val response = preview(token, id, data = mapOf("nombre" to "TORNILLO ^M6"))

        assertThat(response.at<String>("zpl")).isEqualTo("^XA^FDTORNILLO \\^M6^FS^XZ")
    }

    // ── L1 and L3: the queue ───────────────────────────────────────────────────

    @Test
    fun `L1 - requesting a label does not mark it as printed`() {
        val token = manager()
        val id = template(token)

        val response = print(token, id)

        // 202: accepted and pending. No user route marks it printed.
        assertThat(response.status).isEqualTo(202)
        assertThat(response.at<String>("status")).isEqualTo("PENDING")
        val jobId = response.at<List<Int>>("jobIds").single().toLong()
        val job = get("/v1/labels/jobs/$jobId", token)
        assertThat(job.at<String>("status")).isEqualTo("PENDING")
        assertThat(job.at<Int>("attempts")).isZero()
        assertThat(job.at<String?>("printedAt")).isNull()
    }

    @Test
    fun `L3 - with the printer off the job waits and is not lost`() {
        val token = manager()
        val id = template(token)

        val jobId = print(token, id, copies = 2).at<List<Int>>("jobIds").single().toLong()

        // The queue finds it by status: that is what makes the query useful.
        val pendientes = get("/v1/labels/jobs?status=PENDING", token)
        assertThat(pendientes.at<Int>("totalItems")).isEqualTo(1)
        assertThat((pendientes.at<List<Map<String, Any?>>>("items").single()["id"] as Number).toLong())
            .isEqualTo(jobId)
        assertThat(pendientes.at<List<Map<String, Any?>>>("items").single()["copies"]).isEqualTo(2)
        // And there is none printed: nobody self-certifies.
        assertThat(get("/v1/labels/jobs?status=PRINTED", token).at<Int>("totalItems")).isZero()
    }

    @Test
    fun `L5 - reprinting creates a new job linked to the original`() {
        val token = manager()
        val id = template(token)
        val original = print(token, id).at<List<Int>>("jobIds").single().toLong()

        val response = post("/v1/labels/jobs/$original/reprint", mapOf("reason" to "Salió borrosa"), token)

        assertThat(response.status).isEqualTo(202)
        val nuevo = (response.at<Any>("id") as Number).toLong()
        assertThat(nuevo).isNotEqualTo(original)
        assertThat(response.at<String>("status")).isEqualTo("PENDING")
        // Linked to the original: the traceability of a reprint requires it.
        assertThat((response.at<Any>("reprintedFromId") as Number).toLong()).isEqualTo(original)
        // And the original was not touched.
        assertThat(get("/v1/labels/jobs/$original", token).at<String>("status")).isEqualTo("PENDING")
    }

    @Test
    fun `reprinting without a reason is rejected`() {
        val token = manager()
        val id = template(token)
        val original = print(token, id).at<List<Int>>("jobIds").single().toLong()

        val response = post("/v1/labels/jobs/$original/reprint", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
    }

    // ── Enqueueing, before enqueueing ─────────────────────────────────────────

    @Test
    fun `a label with incomplete data is not queued`() {
        val token = manager()
        val id = template(token)

        val response = print(token, id, data = listOf(mapOf("otro" to "X")))

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("MISSING_LABEL_FIELD")
        // The job was NOT created: it is resolved before enqueueing.
        assertThat(db.sql("SELECT count(*) FROM print_jobs").query(Int::class.java).single()).isZero()
    }

    @Test
    fun `an inactive template does not print and the copies have a cap`() {
        val token = manager()
        val inactiva = template(token, code = "vieja", isActive = false)
        val activa = template(token, code = "nueva")

        assertThat(print(token, inactiva).code).isEqualTo("LABEL_TEMPLATE_INACTIVE")

        val copias = print(token, activa, copies = 101)
        assertThat(copias.status).isEqualTo(400)
        assertThat(copias.code).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `a template that does not exist answers LABEL_TEMPLATE_NOT_FOUND`() {
        val token = manager()

        assertThat(preview(token, 999999).code).isEqualTo("LABEL_TEMPLATE_NOT_FOUND")
        assertThat(get("/v1/labels/jobs/999999", token).code).isEqualTo("PRINT_JOB_NOT_FOUND")
    }

    @Test
    fun `the queue requires its own permission`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        // Viewing the queue is `label.queue_view`, which the floor operator does not have.
        assertThat(get("/v1/labels/jobs", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/label-templates", token).code).isEqualTo("PERMISSION_DENIED")
    }
}
