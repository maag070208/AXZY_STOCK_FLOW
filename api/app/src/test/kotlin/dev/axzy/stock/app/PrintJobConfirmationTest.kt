package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.app.support.ApiResponse
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * The printer's confirmation (M06) over real HTTP: **L2 and L4** of
 * docs/flujos/etiquetas.md §5.
 *
 * It is the only route that does not carry a user token but a service credential. The legacy
 * had it open with no credential at all, so anyone on the network could mark
 * jobs as printed: here it is checked that this can no longer happen.
 */
class PrintJobConfirmationTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value
    private val secreto = "clave-de-la-zebra-para-las-pruebas"

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    /**
     * The hash is computed in the test and not imported from the adapter: that way the test pins
     * the **format** of the credential. If the algorithm changed, the seed would stop
     * matching and the test would say so, which is what is wanted from a format like this.
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

    // ── L2: the printer confirms once ─────────────────────────────────────

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

        // The printer's retry does not print twice.
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
        // `?status=FAILED` is the query that makes the queue useful.
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

        // The credential sets the channel, not a header: that is what distinguishes a job
        // marked by a system from one done by a person.
        assertThat(canal).isEqualTo("SERVICE_PRINTER")
        assertThat(clave).isPositive()
    }

    // ── L4: the service key is good for nothing else ───────────────────────

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
        // And the job stays pending: nobody marked it.
        assertThat(get("/v1/labels/jobs/$jobId", token).at<String>("status")).isEqualTo("PENDING")
    }

    @Test
    fun `L4 - the credential does not authenticate a person or choose their channel`() {
        val token = manager()
        val jobId = printed(token, template(token))

        // The service credential is not an access token.
        val comoToken = get("/v1/labels/jobs", headers = mapOf("Authorization" to "Bearer $secreto"))
        assertThat(comoToken.status).isEqualTo(401)

        // And a client cannot declare itself a service channel with a header.
        val canalFalso = get("/v1/labels/jobs", token, mapOf("X-Client-Channel" to "SERVICE_PRINTER"))
        assertThat(canalFalso.status).isEqualTo(400)
        assertThat(canalFalso.code).isEqualTo("INVALID_PARAMETER")

        // The legitimate path is still the credential.
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

        // With a valid credential it can be told apart: the `404` leaks nothing to someone
        // without a credential, because they get the `401` first.
        assertThat(response.status).isEqualTo(404)
        assertThat(response.code).isEqualTo("PRINT_JOB_NOT_FOUND")
    }
}
