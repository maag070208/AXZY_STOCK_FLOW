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
 * La confirmación de la impresora (docs/api/etiquetas.md).
 *
 * **Es la única ruta que no vive bajo `/v1/labels`**, y cuelga del recurso que modifica:
 * el trabajo de impresión. La ruta lleva el `jobId` y no el cuerpo porque el cuerpo
 * describe el cambio, no el recurso, y repetir la llamada no debe imprimir dos veces.
 *
 * **No exige token de usuario sino credencial de servicio**, y esa distinción no es
 * cosmética: la auditoría guarda `SERVICE_PRINTER` como canal y el identificador de la
 * clave, así que un trabajo marcado como impreso sin persona detrás se distingue de uno
 * hecho por alguien. El legacy tenía esta dirección **abierta sin ninguna credencial**.
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

/** Lo que reporta la impresora: el estado y, si falló, por qué. */
data class PrintJobStatusDto(val status: String? = null, val error: String? = null)
