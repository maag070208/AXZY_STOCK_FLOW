package dev.axzy.stock.adapters.web.labeling

import dev.axzy.stock.adapters.web.http.ServiceKeyOnly
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.labeling.ConfirmPrintJob
import dev.axzy.stock.application.shared.RequestOrigin
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The printer's confirmation (docs/api/etiquetas.md).
 *
 * **It is the only route that does not live under `/v1/labels`**, and hangs off the resource it modifies:
 * the print job. The route carries the `jobId` and not the body, because the body
 * describes the change, not the resource, and repeating the call must not print twice.
 *
 * **It does not require a user token but a service credential**, and that distinction is not
 * cosmetic: the audit stores `SERVICE_PRINTER` as the channel and the key's identifier, so a job marked
 * as printed with no person behind it is told apart from one
 * done by someone. The legacy had this address **open with no credential at all**.
 */
@RestController
@RequestMapping("/v1/print-jobs")
class PrintJobController(private val confirmPrintJob: ConfirmPrintJob) {

    @PatchMapping("/{id}")
    @ServiceKeyOnly
    fun confirm(
        @PathVariable id: Long,
        @RequestBody body: PrintJobStatusDto,
        @RequestHeader(name = "X-Service-Key", required = false) serviceKey: String?,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = confirmPrintJob
        .execute(serviceKey, id, body.status, body.error, origin)
        .toResponse { it.toDto() }
}

/** What the printer reports: the status and, if it failed, why. */
data class PrintJobStatusDto(val status: String? = null, val error: String? = null)
