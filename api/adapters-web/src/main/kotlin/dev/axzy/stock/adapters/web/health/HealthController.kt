package dev.axzy.stock.adapters.web.health

import dev.axzy.stock.adapters.web.http.PublicRoute
import dev.axzy.stock.application.health.CheckHealth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class DatabaseHealthDto(val status: String, val latencyMs: Long, val error: String?)

data class HealthDto(
    val status: String,
    val schemaVersion: String?,
    val version: String,
    val database: DatabaseHealthDto,
)

/** `GET /v1/health`: público. Lo consultan Compose y la monitorización (docs/api/salud.md). */
@RestController
class HealthController(private val checkHealth: CheckHealth) {

    @GetMapping("/v1/health")
    @PublicRoute
    fun health(): ResponseEntity<HealthDto> {
        val report = checkHealth.execute()
        val body = HealthDto(
            status = if (report.up) "UP" else "DOWN",
            schemaVersion = report.database.schemaVersion,
            version = report.version,
            database = DatabaseHealthDto(
                status = if (report.database.up) "UP" else "DOWN",
                latencyMs = report.database.latencyMs,
                error = report.database.error,
            ),
        )
        // 503 y no 200 con «DOWN» dentro: un orquestador mira el código, no el cuerpo.
        return ResponseEntity.status(if (report.up) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }
}
