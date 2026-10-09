package dev.axzy.stock.backend.label

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.userFromToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import javax.sql.DataSource

/**
 * Rutas de etiquetas (contrato §8).
 *
 * ── LA ÚNICA RUTA QUE NO EXIGE JWT DE USUARIO ─────────────────────────────
 *
 * `PATCH /v1/print-jobs/{id}` la llama la impresora, y **no cuelga de
 * `/v1/labels`**: es la actualización del estado de UN trabajo, así que cuelga del
 * recurso que modifica. El contrato lo explica: antes era `PATCH /v1/prints`, que
 * «no colgaba de ningún recurso, usaba un verbo que no describía el efecto, y era
 * imposible de descubrir leyendo la documentación de etiquetas».
 *
 * Usa `X-Service-Key` y **no `Authorization`**, para que en los registros quede
 * claro que no es una persona: la auditoría guarda `SERVICE_PRINTER` y el id de la
 * clave, y así un trabajo marcado como impreso sin usuario detrás se distingue de
 * uno hecho por alguien.
 */
fun Route.labelRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker,
) {
    val service = LabelService(dataSource)

    // ═══════════════════════════════════════════════════════════════════════
    // PLANTILLAS
    // ═══════════════════════════════════════════════════════════════════════
    route("/v1/label-templates") {

        get {
            call.requireLabelPermission(permissionChecker, "label.template_view")
            val onlyActive = call.request.queryParameters["onlyActive"]?.toBoolean() ?: false
            call.respond(HttpStatusCode.OK, service.templates(onlyActive))
        }

        get("/{id}") {
            call.requireLabelPermission(permissionChecker, "label.template_view")
            val id = call.labelId("id")
            val plantilla = service.templateById(id)
                ?: throw dev.axzy.stock.backend.error.NotFoundException(
                    "La plantilla $id no existe.",
                    "LABEL_TEMPLATE_NOT_FOUND",
                )
            call.respond(HttpStatusCode.OK, plantilla)
        }

        post {
            call.requireLabelPermission(permissionChecker, "label.template_edit")
            val request = call.receive<LabelTemplateCreateRequest>()
            call.respond(HttpStatusCode.Created, service.createTemplate(request))
        }

        put("/{id}") {
            call.requireLabelPermission(permissionChecker, "label.template_edit")
            val id = call.labelId("id")
            val request = call.receive<LabelTemplateUpdateRequest>()
            call.respond(HttpStatusCode.OK, service.updateTemplate(id, request))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PREVISUALIZACIÓN Y TRABAJOS
    // ═══════════════════════════════════════════════════════════════════════
    route("/v1/labels") {

        post("/preview") {
            call.requireLabelPermission(permissionChecker, "label.print")
            val request = call.receive<LabelPreviewRequest>()
            call.respond(HttpStatusCode.OK, service.preview(request))
        }

        post("/print") {
            call.requireLabelPermission(permissionChecker, "label.print")
            val request = call.receive<LabelPrintRequest>()
            // **`202 Accepted`, no `200`.** El trabajo queda `PENDING` hasta que la
            // impresora confirma: responder `200` daría a entender que la etiqueta
            // ya salió. Es exactamente el defecto del legacy, que la marcaba
            // impresa sin esperar y perdía la etiqueta si la Zebra estaba apagada.
            call.respond(HttpStatusCode.Accepted, service.print(request, call.labelUser()))
        }

        get("/jobs") {
            call.requireLabelPermission(permissionChecker, "label.queue_view")
            val page = call.labelPage()
            val status = call.request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (status != null && status !in PRINT_STATUSES) {
                throw BadRequestException(
                    code = "INVALID_PARAMETER",
                    message = "El parámetro 'status' no admite el valor '$status'.",
                    details = mapOf("status" to status, "allowed" to PRINT_STATUSES.joinToString(", ")),
                )
            }

            val items = service.jobs(status, page.offset, page.size)
            val total = service.countJobs(status)
            call.respond(
                HttpStatusCode.OK,
                PrintJobPageResponse(
                    items = items,
                    page = page.page,
                    size = page.size,
                    totalItems = total,
                    totalPages = if (page.size == 0) 0 else ((total + page.size - 1) / page.size).toInt(),
                ),
            )
        }

        get("/jobs/{id}") {
            call.requireLabelPermission(permissionChecker, "label.queue_view")
            val id = call.labelId("id")
            val trabajo = service.job(id)
                ?: throw dev.axzy.stock.backend.error.NotFoundException(
                    "El trabajo $id no existe.",
                    "PRINT_JOB_NOT_FOUND",
                )
            call.respond(HttpStatusCode.OK, trabajo)
        }

        post("/jobs/{id}/reprint") {
            // `label.reprint` y no `label.print`: reimprimir gasta una segunda
            // etiqueta, y el catálogo los separa a propósito.
            call.requireLabelPermission(permissionChecker, "label.reprint")
            val id = call.labelId("id")
            val request = call.receive<ReprintRequest>()
            // **Crea un trabajo NUEVO**, no reutiliza el registro: la trazabilidad
            // de una reimpresión exige saber quién la pidió y por qué.
            call.respond(HttpStatusCode.Accepted, service.reprint(id, request.reason, call.labelUser()))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LA IMPRESORA — credencial de servicio, no JWT
    // ═══════════════════════════════════════════════════════════════════════
    route("/v1/print-jobs") {
        patch("/{id}") {
            // **`X-Service-Key` y no `Authorization`.** El legacy tenía este
            // endpoint ABIERTO sin credencial: cualquiera en la red podía marcar
            // trabajos como impresos.
            val clave = call.request.headers["X-Service-Key"]
            if (!service.isValidServiceKey(clave)) {
                throw UnauthorizedException(
                    code = "INVALID_SERVICE_KEY",
                    message = "Falta o no es válida la cabecera X-Service-Key. Esta ruta la llama la " +
                        "impresora, no una persona.",
                )
            }

            val id = call.labelId("id")
            val request = call.receive<PrintJobUpdateRequest>()
            call.respond(HttpStatusCode.OK, service.updateJobStatus(id, request))
        }
    }
}

/** Los estados de un trabajo de impresión, para validar el filtro. */
private val PRINT_STATUSES = listOf("PENDING", "SENT", "PRINTED", "FAILED")

private data class LabelPage(val page: Int, val size: Int) {
    val offset: Int get() = page * size
}

private fun ApplicationCall.labelPage(): LabelPage {
    val brutoPagina = request.queryParameters["page"]
    val brutoTamano = request.queryParameters["size"]

    val pagina = brutoPagina?.trim()?.toIntOrNull()
        ?: if (brutoPagina.isNullOrBlank()) 0 else throw labelPaginationError("page", brutoPagina)
    val tamano = brutoTamano?.trim()?.toIntOrNull()
        ?: if (brutoTamano.isNullOrBlank()) 50 else throw labelPaginationError("size", brutoTamano)

    if (pagina < 0) throw labelPaginationError("page", brutoPagina)
    if (tamano < 1 || tamano > 200) throw labelPaginationError("size", brutoTamano)

    return LabelPage(pagina, tamano)
}

private fun labelPaginationError(parametro: String, valor: String?) = BadRequestException(
    code = "INVALID_PAGINATION",
    message = "El parámetro '$parametro' no es válido. Se recibió '$valor'. " +
        "`page` empieza en 0 y `size` admite de 1 a 200.",
    details = mapOf(parametro to (valor ?: "")),
)

private fun ApplicationCall.labelId(nombre: String): Long =
    parameters[nombre]?.toLongOrNull()
        ?: throw BadRequestException("INVALID_ID", "El identificador no es un número.")

private fun ApplicationCall.labelUser(): Long? = userFromToken()

private suspend fun ApplicationCall.requireLabelPermission(
    verifier: PermissionChecker,
    permission: String,
) {
    when (val decision = verifier.check(this, permission)) {
        is PermissionDecision.Permitted -> Unit
        is PermissionDecision.Denied ->
            if (decision.code == "UNAUTHENTICATED") {
                throw UnauthorizedException(decision.code, decision.message)
            } else {
                throw ForbiddenException(decision.code, decision.message)
            }
    }
}
