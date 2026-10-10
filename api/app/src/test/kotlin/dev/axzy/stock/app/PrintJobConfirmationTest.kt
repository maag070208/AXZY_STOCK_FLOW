package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.app.support.ApiResponse
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * La confirmación de la impresora (M06) por HTTP real: **L2 y L4** de
 * docs/flujos/etiquetas.md §5.
 *
 * Es la única ruta que no lleva token de usuario sino credencial de servicio. El legacy
 * la tenía abierta sin ninguna credencial, así que cualquiera en la red podía marcar
 * trabajos como impresos: aquí se comprueba que eso ya no se puede.
 */
class PrintJobConfirmationTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value
    private val secreto = "clave-de-la-zebra-para-las-pruebas"

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    /**
     * El hash se calcula en la prueba y no se importa del adaptador: así la prueba fija
     * el **formato** de la credencial. Si el algoritmo cambiara, la siembra dejaría de
     * coincidir y la prueba lo diría, que es lo que se quiere de un formato así.
     */
    private fun sha256(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    @BeforeEach
    fun seedServiceKey() {
        db.sql(
            """
            INSERT INTO service_api_keys (name, key_hash, scopes)
            VALUES ('Zebra del almacén', :hash, ARRAY['labels:confirm'])
            """.trimIndent(),
        ).param("hash", sha256(secreto)).update()
    }

    private fun template(token: String): Long =
        post(
            "/v1/label-templates",
            mapOf("code" to "receipt", "name" to "Recibo", "zpl" to "^XA^FD{{productCode}}^FS^XZ"),
            token,
        ).at<Int>("id").toLong()

    private fun printed(token: String, templateId: Long): Long =
        post("/v1/labels/print", mapOf("templateId" to templateId, "data" to listOf(mapOf("productCode" to "ABRAA1B"))), token)
            .at<List<Int>>("jobIds").single().toLong()

    private fun confirm(jobId: Long, key: String?, body: Map<String, Any?>): ApiResponse =
        patch("/v1/print-jobs/$jobId", body, headers = key?.let { mapOf("X-Service-Key" to it) } ?: emptyMap())

    // ── L2: la impresora confirma una vez ───────────────────────────────────

    @Test
    fun `L2 - the printer confirms and the label becomes printed exactly once`() {
        val token = manager()
        val jobId = printed(token, template(token))

        val primera = confirm(jobId, secreto, mapOf("status" to "PRINTED"))
        val segunda = confirm(jobId, secreto, mapOf("status" to "PRINTED"))

        assertThat(primera.status).describedAs(primera.raw).isEqualTo(200)
        assertThat(primera.at<String>("status")).isEqualTo("PRINTED")
        assertThat(primera.at<Int>("attempts")).isEqualTo(1)
        assertThat(primera.at<String>("printedAt")).isNotBlank()

        // El reintento de la impresora no imprime dos veces.
        assertThat(segunda.status).isEqualTo(409)
        assertThat(segunda.code).isEqualTo("LABEL_ALREADY_PRINTED")
        assertThat(get("/v1/labels/jobs/$jobId", token).at<Int>("attempts")).isEqualTo(1)
    }

    @Test
    fun `a printer failure leaves the job locatable`() {
        val token = manager()
        val jobId = printed(token, template(token))

        val response = confirm(jobId, secreto, mapOf("status" to "FAILED", "error" to "Sin papel"))

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("FAILED")
        assertThat(response.at<String>("lastError")).isEqualTo("Sin papel")
        // `?status=FAILED` es la consulta que hace útil la cola.
        assertThat(get("/v1/labels/jobs?status=FAILED", token).at<Int>("totalItems")).isEqualTo(1)
    }

    @Test
    fun `the confirmation is recorded in the audit log with its channel and its credential`() {
        val token = manager()
        val jobId = printed(token, template(token))

        confirm(jobId, secreto, mapOf("status" to "PRINTED"))

        val canal = db.sql(
            "SELECT source_channel::text FROM audit_log WHERE action = 'PRINT_JOB_PRINTED'",
        ).query(String::class.java).single()
        val clave = db.sql(
            "SELECT source_service_key_id FROM audit_log WHERE action = 'PRINT_JOB_PRINTED'",
        ).query(Long::class.java).single()

        // El canal lo fija la credencial, no una cabecera: es lo que distingue un trabajo
        // marcado por un sistema de uno hecho por una persona.
        assertThat(canal).isEqualTo("SERVICE_PRINTER")
        assertThat(clave).isPositive()
    }

    // ── L4: la clave de servicio no sirve para otra cosa ────────────────────

    @Test
    fun `L4 - without a credential, or with one that is not valid, the route answers nothing else`() {
        val token = manager()
        val jobId = printed(token, template(token))

        val sinClave = confirm(jobId, null, mapOf("status" to "PRINTED"))
        val claveMala = confirm(jobId, "no-es-la-clave", mapOf("status" to "PRINTED"))

        assertThat(sinClave.status).isEqualTo(401)
        assertThat(sinClave.code).isEqualTo("INVALID_SERVICE_KEY")
        assertThat(claveMala.status).isEqualTo(401)
        assertThat(claveMala.code).isEqualTo("INVALID_SERVICE_KEY")
        // Y el trabajo sigue pendiente: nadie lo marcó.
        assertThat(get("/v1/labels/jobs/$jobId", token).at<String>("status")).isEqualTo("PENDING")
    }

    @Test
    fun `L4 - the credential does not authenticate a person or choose their channel`() {
        val token = manager()
        val jobId = printed(token, template(token))

        // La credencial de servicio no es un token de acceso.
        val comoToken = get("/v1/labels/jobs", headers = mapOf("Authorization" to "Bearer $secreto"))
        assertThat(comoToken.status).isEqualTo(401)

        // Y un cliente no puede declararse canal de servicio con una cabecera.
        val canalFalso = get("/v1/labels/jobs", token, mapOf("X-Client-Channel" to "SERVICE_PRINTER"))
        assertThat(canalFalso.status).isEqualTo(400)
        assertThat(canalFalso.code).isEqualTo("INVALID_PARAMETER")

        // La vía legítima sigue siendo la credencial.
        assertThat(confirm(jobId, secreto, mapOf("status" to "PRINTED")).status).isEqualTo(200)
    }

    @Test
    fun `a revoked credential stops being valid`() {
        val token = manager()
        val jobId = printed(token, template(token))
        db.sql("UPDATE service_api_keys SET revoked_at = now()").update()

        val response = confirm(jobId, secreto, mapOf("status" to "PRINTED"))

        assertThat(response.status).isEqualTo(401)
        assertThat(response.code).isEqualTo("INVALID_SERVICE_KEY")
    }

    @Test
    fun `a status that is neither PRINTED nor FAILED is rejected`() {
        val token = manager()
        val jobId = printed(token, template(token))

        val response = confirm(jobId, secreto, mapOf("status" to "PENDING"))

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
    }

    @Test
    fun `a job that does not exist with a valid credential answers PRINT_JOB_NOT_FOUND`() {
        val response = confirm(999999, secreto, mapOf("status" to "PRINTED"))

        // Con credencial válida sí se puede distinguir: el `404` no filtra nada a quien
        // no tiene credencial porque antes recibe el `401`.
        assertThat(response.status).isEqualTo(404)
        assertThat(response.code).isEqualTo("PRINT_JOB_NOT_FOUND")
    }
}
