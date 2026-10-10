package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Etiquetas (M06) por HTTP real.
 *
 * L1, L3 y L5 de docs/flujos/etiquetas.md §5. **L2 y L4 necesitan la confirmación de la
 * impresora** (`PATCH /v1/print-jobs/{id}` con `X-Service-Key`), que es lo que falta del
 * módulo: aquí se comprueba justo lo contrario —que ninguna ruta de usuario marca una
 * etiqueta como impresa—, que es la mitad que sí está.
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
    fun `el listado no trae el ZPL y el detalle si`() {
        val token = manager()
        val id = template(token)

        // El listado es un ARREGLO sin sobre de paginación: catálogo cerrado.
        assertThat(get("/v1/label-templates", token).raw).startsWith("[")
        assertThat(get("/v1/label-templates", token).raw).doesNotContain("zpl")

        val detalle = get("/v1/label-templates/$id", token)
        assertThat(detalle.at<String>("code")).isEqualTo("receipt")
        assertThat(detalle.at<String>("zpl")).contains("^XA")
        assertThat(detalle.at<Map<String, Any?>>("fieldSchema")).containsKey("productCode")
    }

    @Test
    fun `un ZPL sin cierre no se guarda`() {
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
    fun `la edicion es parcial y deja lo que no se envia`() {
        val token = manager()
        val id = template(token)

        val response = put("/v1/label-templates/$id", mapOf("name" to "Otro nombre"), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("name")).isEqualTo("Otro nombre")
        // El ZPL y el código siguen como estaban.
        assertThat(response.at<String>("code")).isEqualTo("receipt")
        assertThat(response.at<String>("zpl")).contains("^XA")
    }

    // ── Previsualización ────────────────────────────────────────────────────

    @Test
    fun `la previsualizacion resuelve el ZPL y el tamaño lo pone el servidor`() {
        val token = manager()
        val id = template(token, zpl = "^XA^FD{{productCode}}^FS^XZ")

        val response = preview(token, id)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("zpl")).isEqualTo("^XA^FDABRAA1B^FS^XZ")
        val preview = response.at<Map<String, Any?>>("preview")
        // 4x6 pulgadas en milímetros, calculado en el servidor.
        assertThat((preview["widthMm"] as Number).toDouble()).isEqualTo(101.6)
        assertThat((preview["heightMm"] as Number).toDouble()).isEqualTo(152.4)
        assertThat(preview["copies"]).isEqualTo(1)
    }

    @Test
    fun `un campo obligatorio que falta no produce una etiqueta a medias`() {
        val token = manager()
        val id = template(token)

        val response = preview(token, id, data = emptyMap())

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("MISSING_LABEL_FIELD")
    }

    @Test
    fun `un caracter de control en el dato no rompe la etiqueta`() {
        val token = manager()
        // El ZPL usa `^` para sus comandos: un nombre con `^` cerraría el campo antes
        // de tiempo y el resto se interpretaría como un comando de impresora.
        val id = template(token, zpl = "^XA^FD{{nombre}}^FS^XZ", schema = mapOf("nombre" to mapOf("type" to "string")))

        val response = preview(token, id, data = mapOf("nombre" to "TORNILLO ^M6"))

        assertThat(response.at<String>("zpl")).isEqualTo("^XA^FDTORNILLO \\^M6^FS^XZ")
    }

    // ── L1 y L3: la cola ────────────────────────────────────────────────────

    @Test
    fun `L1 - pedir una etiqueta no la marca como impresa`() {
        val token = manager()
        val id = template(token)

        val response = print(token, id)

        // 202: aceptado y pendiente. Ninguna ruta de usuario la marca impresa.
        assertThat(response.status).isEqualTo(202)
        assertThat(response.at<String>("status")).isEqualTo("PENDING")
        val jobId = response.at<List<Int>>("jobIds").single().toLong()
        val job = get("/v1/labels/jobs/$jobId", token)
        assertThat(job.at<String>("status")).isEqualTo("PENDING")
        assertThat(job.at<Int>("attempts")).isZero()
        assertThat(job.at<String?>("printedAt")).isNull()
    }

    @Test
    fun `L3 - con la impresora apagada el trabajo espera y no se pierde`() {
        val token = manager()
        val id = template(token)

        val jobId = print(token, id, copies = 2).at<List<Int>>("jobIds").single().toLong()

        // La cola lo encuentra por estado: es lo que hace útil la consulta.
        val pendientes = get("/v1/labels/jobs?status=PENDING", token)
        assertThat(pendientes.at<Int>("totalItems")).isEqualTo(1)
        assertThat((pendientes.at<List<Map<String, Any?>>>("items").single()["id"] as Number).toLong())
            .isEqualTo(jobId)
        assertThat(pendientes.at<List<Map<String, Any?>>>("items").single()["copies"]).isEqualTo(2)
        // Y no hay ninguno impreso: nadie se autocertifica.
        assertThat(get("/v1/labels/jobs?status=PRINTED", token).at<Int>("totalItems")).isZero()
    }

    @Test
    fun `L5 - reimprimir crea un trabajo nuevo enlazado al original`() {
        val token = manager()
        val id = template(token)
        val original = print(token, id).at<List<Int>>("jobIds").single().toLong()

        val response = post("/v1/labels/jobs/$original/reprint", mapOf("reason" to "Salió borrosa"), token)

        assertThat(response.status).isEqualTo(202)
        val nuevo = (response.at<Any>("id") as Number).toLong()
        assertThat(nuevo).isNotEqualTo(original)
        assertThat(response.at<String>("status")).isEqualTo("PENDING")
        // Enlazado al original: la trazabilidad de una reimpresión lo exige.
        assertThat((response.at<Any>("reprintedFromId") as Number).toLong()).isEqualTo(original)
        // Y el original no se tocó.
        assertThat(get("/v1/labels/jobs/$original", token).at<String>("status")).isEqualTo("PENDING")
    }

    @Test
    fun `reimprimir sin motivo se rechaza`() {
        val token = manager()
        val id = template(token)
        val original = print(token, id).at<List<Int>>("jobIds").single().toLong()

        val response = post("/v1/labels/jobs/$original/reprint", emptyMap<String, Any>(), token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
    }

    // ── El encolado, antes de encolar ───────────────────────────────────────

    @Test
    fun `no se encola una etiqueta con datos incompletos`() {
        val token = manager()
        val id = template(token)

        val response = print(token, id, data = listOf(mapOf("otro" to "X")))

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("MISSING_LABEL_FIELD")
        // El trabajo NO se creó: se resuelve antes de encolar.
        assertThat(db.sql("SELECT count(*) FROM print_jobs").query(Int::class.java).single()).isZero()
    }

    @Test
    fun `una plantilla inactiva no imprime y las copias tienen tope`() {
        val token = manager()
        val inactiva = template(token, code = "vieja", isActive = false)
        val activa = template(token, code = "nueva")

        assertThat(print(token, inactiva).code).isEqualTo("LABEL_TEMPLATE_INACTIVE")

        val copias = print(token, activa, copies = 101)
        assertThat(copias.status).isEqualTo(400)
        assertThat(copias.code).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `una plantilla que no existe responde LABEL_TEMPLATE_NOT_FOUND`() {
        val token = manager()

        assertThat(preview(token, 999999).code).isEqualTo("LABEL_TEMPLATE_NOT_FOUND")
        assertThat(get("/v1/labels/jobs/999999", token).code).isEqualTo("PRINT_JOB_NOT_FOUND")
    }

    @Test
    fun `la cola exige su propio permiso`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        // Ver la cola es `label.queue_view`, que el operador de piso no tiene.
        assertThat(get("/v1/labels/jobs", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/label-templates", token).code).isEqualTo("PERMISSION_DENIED")
    }
}
