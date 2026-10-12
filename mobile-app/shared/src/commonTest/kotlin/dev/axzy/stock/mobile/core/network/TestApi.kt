package dev.axzy.stock.mobile.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/**
 * An API for tests: an [ApiClient] over an engine that answers what the test prepared and records what it was
 * sent. Requests go through the real client, so headers, bodies and decoding are the ones the app uses.
 */
class TestApi {

    class Call(val method: String, val path: String, val authorization: String?, val channel: String?, val body: String)

    private class Answer(val status: Int, val body: String)

    /** Every request the client sent, in order. */
    val calls = mutableListOf<Call>()

    private val answers = mutableMapOf<String, ArrayDeque<Answer>>()
    private val unreachable = mutableSetOf<String>()

    val client = ApiClient(
        baseUrl = "http://api.test",
        engine = MockEngine { request ->
            val path = request.url.encodedPathAndQuery
            val route = "${request.method.value} $path"
            calls += Call(
                method = request.method.value,
                path = path,
                authorization = request.headers[HttpHeaders.Authorization],
                channel = request.headers["X-Client-Channel"],
                body = (request.body as? TextContent)?.text.orEmpty(),
            )
            if (route in unreachable) throw IllegalStateException("No network for $route")
            val prepared = answers[route] ?: throw IllegalStateException("No answer prepared for $route")
            // Answers are given in the order they were prepared; the last one keeps answering.
            val answer = if (prepared.size > 1) prepared.removeFirst() else prepared.first()
            respond(
                content = answer.body,
                status = HttpStatusCode.fromValue(answer.status),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    fun answer(method: String, path: String, status: Int = 200, body: String = "") {
        unreachable -= "$method $path"
        answers.getOrPut("$method $path") { ArrayDeque() }.addLast(Answer(status, body))
    }

    /** Replaces whatever was prepared for the route with this one answer. */
    fun answerNow(method: String, path: String, status: Int = 200, body: String = "") {
        answers.remove("$method $path")
        answer(method, path, status, body)
    }

    /** Makes the route fail as a network without answer would. */
    fun cutOff(method: String, path: String) {
        unreachable += "$method $path"
    }

    fun callsTo(method: String, path: String): List<Call> = calls.filter { it.method == method && it.path == path }
}

// The bodies of the API, as its documentation shows them.

fun errorJson(code: String, details: String = "") =
    if (details.isEmpty()) """{"code":"$code","message":"English text the app never shows"}"""
    else """{"code":"$code","message":"English text the app never shows","details":{$details}}"""

fun sessionJson(access: String, refresh: String, mustChangePassword: Boolean = false) = """
    {"accessToken":"$access","refreshToken":"$refresh","expiresIn":900,
     "user":{"id":7,"username":"jperez","name":"Juan Pérez","roles":["WAREHOUSE_MANAGER"],
             "permissions":["invoice.view","invoice.capture"],"scopes":{"invoice.capture":"AREA"},
             "warehouses":[1],"mustChangePassword":$mustChangePassword}}
""".trimIndent()

fun invoiceHeaderJson(id: Long, status: String, totalLines: Int, pendingLines: Int) = """
    {"id":$id,"invoiceNumber":"F-$id","receiptDate":"2026-10-07","invoiceType":"NATIONAL","status":"$status",
     "customerCode":null,"concept":null,"totalLines":$totalLines,"pendingApprovalLines":$pendingLines,
     "createdAt":"2026-10-07T21:24:37.688Z"}
""".trimIndent()

fun invoicePageJson(vararg headers: String) =
    """{"items":[${headers.joinToString(",")}],"page":0,"size":200,"totalItems":${headers.size},"totalPages":1}"""

fun invoiceLineJson(
    id: Long,
    status: String,
    quantity: String = "100.000",
    unitsPerBox: String = "50.000",
    physicalQuantity: String? = null,
    physicalBoxQuantity: String? = null,
) = """
    {"id":$id,"productId":$id,"productCode":"ABRAA${id}B","productName":"Clamp $id","container":"CONT-01",
     "quantity":"$quantity","boxQuantity":"2.000","unitsPerBox":"$unitsPerBox",
     "physicalQuantity":${physicalQuantity?.let { "\"$it\"" }},"physicalBoxQuantity":${physicalBoxQuantity?.let { "\"$it\"" }},
     "locatedQuantity":"0.000","difference":null,"status":"$status",
     "capturedBy":null,"capturedByName":null,"capturedAt":null,"approvedBy":null,"approvedByName":null,
     "approvedAt":null,"adjustmentReason":null,"locationDisplayName":null,
     "assignedUserId":null,"assignedUserName":null}
""".trimIndent()

fun invoiceDetailJson(id: Long, status: String, pendingLines: Int, vararg lines: String) = """
    {"id":$id,"invoiceNumber":"F-$id","receiptDate":"2026-10-07","invoiceType":"NATIONAL","status":"$status",
     "customerCode":null,"concept":null,"totalLines":${lines.size},"pendingApprovalLines":$pendingLines,
     "createdAt":"2026-10-07T21:24:37.688Z","lines":[${lines.joinToString(",")}]}
""".trimIndent()
