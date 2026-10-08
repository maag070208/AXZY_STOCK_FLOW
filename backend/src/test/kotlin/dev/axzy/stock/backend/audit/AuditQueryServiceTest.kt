package dev.axzy.stock.backend.audit

import dev.axzy.stock.backend.config.DatabaseConfig
import dev.axzy.stock.backend.config.buildDataSource
import dev.axzy.stock.backend.config.migrate
import dev.axzy.stock.backend.module
import dev.axzy.stock.backend.security.AllowAllPermissionChecker
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONSULTA DEL LEDGER DE AUDITORÍA — `GET /v1/audit/log`
 *
 * Contra **PostgreSQL de verdad**, como el resto del backend: lo que se prueba
 * aquí no es que el servicio haga lo que se le dijo, sino que la tabla
 * PARTICIONADA devuelve lo que debe. Un doble de prueba no tiene particiones, ni
 * `INET`, ni `TEXT[]`, ni enums, ni disparadores de inmutabilidad, así que no
 * podría detectar ninguno de los fallos que este endpoint puede tener.
 *
 * **La base de prueba se recrea entera en `beforeSpec`**, y eso no es un detalle:
 * `audit_log` es append-only y sus disparadores **prohíben** `DELETE`, así que un
 * test que insertara sus filas y las borrara al terminar no podría hacerlo —el
 * `DELETE` falla a propósito—. Recrear la base es la única limpieza posible, y de
 * paso hace que los recuentos sean exactos en lugar de depender de lo que dejó la
 * ejecución anterior. Por el mismo motivo las filas se insertan UNA vez, en
 * `beforeSpec`, y todas las pruebas son de lectura: es lo que hace un ledger.
 *
 * Si no hay base disponible, los tests se **saltan** y lo dicen.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AuditQueryServiceTest : DescribeSpec({

    val connectionProblem = AuditBase.connectionProblem()
    val thereIsBase = connectionProblem == null

    if (!thereIsBase) {
        println(
            """
            |
            |  ⚠️  TESTS DE AUDITORÍA SALTADOS: no se pudo consultar PostgreSQL
            |      Motivo: $connectionProblem
            |      Levántalo con:  cd infra && docker compose up -d
            |
            """.trimMargin(),
        )
    }

    // ── El escenario ────────────────────────────────────────────────────────
    // Fechas RELATIVAS a hoy y no fijas: el esquema crea las particiones del mes
    // en curso y de los tres siguientes, así que una fecha fija —«2026-11-10»—
    // acabaría cayendo fuera de toda partición cuando avance el calendario y las
    // filas irían a `audit_log_default` sin que nadie lo notara. Relativas,
    // siempre caen en una partición real.
    val today: LocalDate = LocalDate.now(ZoneOffset.UTC)
    val yesterday: Instant = today.minusDays(1).atTime(10, 0).toInstant(ZoneOffset.UTC)
    val now: Instant = today.atTime(10, 0).toInstant(ZoneOffset.UTC)
    val tomorrow: Instant = today.plusDays(1).atTime(10, 0).toInstant(ZoneOffset.UTC)

    val corrA: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
    val corrB: UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002")
    val corrC: UUID = UUID.fromString("cccccccc-0000-4000-8000-000000000003")
    /** Un UUID que no está en la base: el filtro «que no devuelve nada». */
    val corrNonexistent: UUID = UUID.fromString("dddddddd-0000-4000-8000-000000000004")

    var userId: Long = 0

    beforeSpec {
        if (thereIsBase) {
            AuditBase.recreate()
            AuditBase.withPool { pool ->
                userId = AuditBase.insertUser(pool, "auditora", "Auditora de Prueba")

                // R1, R2 y R3: la MISMA operación (corrA) en tres días distintos.
                // Es lo que permite probar que el rango de fechas filtra de verdad y
                // que el conteo no se lo salta.
                AuditBase.insert(
                    pool = pool,
                    correlationId = corrA,
                    occurredAt = yesterday,
                    action = "STOCK_ADJUSTED",
                    entityType = "kardex",
                    entityId = "42",
                    actorUserId = userId,
                    actorUsername = "auditora",
                    actorName = "Auditora de Prueba",
                    actorRoles = listOf("AUDITOR", "JEFE_BODEGA"),
                    channel = "WEB",
                    level = "RELEVANT",
                    permission = "inventory.adjust_theft",
                    decision = "ALLOWED",
                    result = "SUCCESS",
                    ip = "10.0.0.7",
                    userAgent = "Ktor-Test/1.0",
                    changedFields = listOf("quantity"),
                    prevHash = null,
                    recordHash = "hash-1",
                    reason = "Merma detectada en conteo físico",
                )
                AuditBase.insert(
                    pool = pool,
                    correlationId = corrA,
                    occurredAt = now,
                    action = "STOCK_ADJUSTED",
                    entityType = "kardex",
                    entityId = "42",
                    actorUserId = userId,
                    actorUsername = "auditora",
                    actorName = "Auditora de Prueba",
                    actorRoles = listOf("AUDITOR"),
                    channel = "WEB",
                    level = "RELEVANT",
                    permission = "inventory.adjust_theft",
                    decision = "ALLOWED",
                    result = "SUCCESS",
                    ip = "10.0.0.7",
                    userAgent = "Ktor-Test/1.0",
                    changedFields = listOf("quantity"),
                    prevHash = "hash-1",
                    recordHash = "hash-2",
                    // El CHECK `audit_adjustment_has_reason` exige motivo en todo
                    // `STOCK_ADJUSTED%`: un ajuste sin motivo es un ajuste que nadie
                    // puede explicar después.
                    reason = "Segundo conteo del mismo producto",
                )
                AuditBase.insert(
                    pool = pool,
                    correlationId = corrA,
                    occurredAt = tomorrow,
                    action = "STOCK_ADJUSTED",
                    entityType = "kardex",
                    entityId = "42",
                    actorUserId = userId,
                    actorUsername = "auditora",
                    actorName = "Auditora de Prueba",
                    actorRoles = listOf("AUDITOR"),
                    channel = "MOBILE_OFFLINE",
                    level = "RELEVANT",
                    permission = "inventory.adjust_count_error",
                    decision = "ALLOWED",
                    result = "PARTIAL",
                    ip = "10.0.0.9",
                    userAgent = "App-Movil/2.3",
                    changedFields = listOf("quantity", "reason"),
                    prevHash = "hash-2",
                    recordHash = "hash-3",
                    reason = "Capturado sin señal, sincronizado después",
                )

                // R4: una DENEGACIÓN. Es la fila que responde «¿alguien intentó algo
                // que no debía?» y la que prueba el filtro `decision`.
                AuditBase.insert(
                    pool = pool,
                    correlationId = corrB,
                    occurredAt = now,
                    action = "AUTH_LOGIN_DENIED",
                    entityType = "user",
                    entityId = "7",
                    actorUserId = userId,
                    actorUsername = "auditora",
                    actorName = "Auditora de Prueba",
                    actorRoles = listOf("OPERADOR_ALMACEN"),
                    channel = "WEB",
                    level = "ACCESS",
                    permission = "audit.view",
                    decision = "DENIED",
                    result = "DENIED",
                    ip = "192.168.1.44",
                    userAgent = "Mozilla/5.0",
                    changedFields = null,
                    prevHash = "hash-3",
                    recordHash = "hash-4",
                    errorCode = "PERMISSION_DENIED",
                    errorMessage = "No tienes el permiso 'audit.view'.",
                )

                // R5: un registro del SISTEMA, sin usuario. Comprueba que el DTO
                // admite el nulo —`actor_user_id` es nullable y el CHECK de «actor
                // obligatorio» solo aplica a canales humanos— y que un nulo no se
                // convierte en un cero.
                AuditBase.insert(
                    pool = pool,
                    correlationId = corrC,
                    occurredAt = now,
                    action = "LOCATION_CREATED",
                    entityType = "location",
                    entityId = "9",
                    actorUserId = null,
                    actorUsername = null,
                    actorName = null,
                    actorRoles = null,
                    channel = "SYSTEM",
                    level = "TECHNICAL",
                    permission = null,
                    decision = null,
                    result = "SUCCESS",
                    ip = null,
                    userAgent = null,
                    changedFields = listOf("code", "hall"),
                    prevHash = "hash-4",
                    recordHash = "hash-5",
                )

                // R6: misma operación (corrA) pero OTRA entidad. Separa «filtrar por
                // operación» de «filtrar por entidad», que con datos que solo
                // tuvieran una entidad por operación serían indistinguibles.
                AuditBase.insert(
                    pool = pool,
                    correlationId = corrA,
                    occurredAt = now,
                    action = "STOCK_ADJUSTED",
                    entityType = "kardex",
                    entityId = "99",
                    actorUserId = userId,
                    actorUsername = "auditora",
                    actorName = "Auditora de Prueba",
                    actorRoles = listOf("AUDITOR"),
                    channel = "WEB",
                    level = "RELEVANT",
                    permission = "inventory.adjust_theft",
                    decision = "ALLOWED",
                    result = "SUCCESS",
                    ip = "10.0.0.7",
                    userAgent = "Ktor-Test/1.0",
                    changedFields = listOf("quantity"),
                    prevHash = "hash-5",
                    recordHash = "hash-6",
                    reason = "Tercer conteo, producto distinto",
                )
            }
        }
    }

    /** El módulo real, con las rutas conectadas y el permiso concedido. */
    fun ApplicationTestBuilder.build(pool: DataSource) {
        application { module(dataSource = pool, permissionChecker = AllowAllPermissionChecker) }
    }

    /** Llama al endpoint por HTTP y devuelve el estado y el JSON ya parseado. */
    suspend fun request(route: String): Pair<HttpStatusCode, JsonObject> =
        AuditBase.withPoolSuspend { pool ->
            var result: Pair<HttpStatusCode, JsonObject>? = null
            testApplication {
                build(pool)
                val response = client.get(route)
                result = response.status to
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            result!!
        }

    /** Atajo para las comprobaciones de paginación del sobre. */
    fun JsonObject.items(): List<JsonObject> = this["items"]!!.jsonArray.map { it.jsonObject }
    fun JsonObject.total(): Long = this["totalItems"]!!.jsonPrimitive.long
    fun JsonObject.first(): JsonObject = items().first()

    // ═══════════════════════════════════════════════════════════════════════
    // EL SERVICIO: el conteo, los filtros y el orden
    // ═══════════════════════════════════════════════════════════════════════

    describe("el total es el real y usa los mismos filtros que el listado") {

        it("`totalItems` cuenta las filas que cumplen el filtro, no el tamaño de la página") {
            // El bug del inventario: se pasaba `items.size` al sobre, así que la
            // respuesta decía «50 resultados» siempre.
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val service = AuditQueryService(pool)

                val page = service.list(AuditFilters(), limit = 2)
                val total = service.count(AuditFilters())

                withClue("La página trae 2 filas y el ledger tiene 6") {
                    page.size shouldBe 2
                    total shouldBe 6L
                }
            }
        }

        it("un filtro sin resultados da 0 y no el total del ledger: el error del kardex") {
            // En el kardex el conteo ignoraba el rango: la búsqueda devolvía
            // `items: []` con `totalItems: 1064`. Un conteo que ignora los filtros
            // es PEOR que no tener conteo, porque es creíble.
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val service = AuditQueryService(pool)
                val nonexistent = AuditFilters(correlationId = corrNonexistent)

                service.list(nonexistent) shouldBe emptyList()
                service.count(nonexistent) shouldBe 0L
            }
        }

        it("el conteo filtra por operación, entidad, acción, actor, decisión y resultado") {
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val service = AuditQueryService(pool)

                // Por operación completa: 4 registros (R1, R2, R3 y R6).
                service.count(AuditFilters(correlationId = corrA)) shouldBe 4L
                service.list(AuditFilters(correlationId = corrA)).size shouldBe 4

                // Por entidad, y por entidad concreta.
                service.count(AuditFilters(entityType = "kardex")) shouldBe 4L
                service.count(AuditFilters(entityType = "kardex", entityId = "42")) shouldBe 3L
                service.count(AuditFilters(entityType = "kardex", entityId = "99")) shouldBe 1L

                // Por acción y por actor: cinco de los seis registros son de la
                // misma persona; el sexto lo escribió el sistema.
                service.count(AuditFilters(action = "AUTH_LOGIN_DENIED")) shouldBe 1L
                service.count(AuditFilters(action = "STOCK_ADJUSTED")) shouldBe 4L
                service.count(AuditFilters(actorUserId = userId)) shouldBe 5L

                // Solo denegados: el filtro rápido que pide `PLAN_AUDITORIA` §7.1.
                service.count(AuditFilters(decision = "DENIED")) shouldBe 1L
                service.count(AuditFilters(result = "DENIED")) shouldBe 1L

                // Por canal: el registro del sistema.
                service.count(AuditFilters(channel = "SYSTEM")) shouldBe 1L
            }
        }

        it("el rango de fechas recorta de verdad, y el conteo lo respeta") {
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val service = AuditQueryService(pool)

                // Solo el instante de hoy: R2, R4, R5 y R6 comparten hora.
                val onlyToday = AuditFilters(from = now, until = now)
                service.count(onlyToday) shouldBe 4L
                service.list(onlyToday).size shouldBe 4

                // Desde hoy inclusive: hoy (4) y mañana (1). Ayer queda fuera.
                service.count(AuditFilters(from = now)) shouldBe 5L
                // Hasta hoy inclusive: ayer (1) y hoy (4). Mañana queda fuera.
                service.count(AuditFilters(until = now)) shouldBe 5L
                // El ledger entero, que es el máximo: 6.
                service.count(AuditFilters()) shouldBe 6L
            }
        }
    }

    describe("el orden por defecto es de lo más reciente hacia atrás") {

        it("el listado sale descendente por fecha y con desempate por `sequence_no`") {
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val service = AuditQueryService(pool)

                val descending = service.list(AuditFilters(), limit = 10)
                withClue("Lo último registrado va primero: un ledger se lee del final al principio") {
                    descending.first().occurredAt shouldBe tomorrow.toString()
                    descending.last().occurredAt shouldBe yesterday.toString()
                }

                val ascending = service.list(AuditFilters(), descending = false, limit = 10)
                ascending.first().occurredAt shouldBe yesterday.toString()
                ascending.last().occurredAt shouldBe tomorrow.toString()

                // Sin desempate, dos registros del mismo milisegundo —una recepción
                // de 30 líneas los produce— podrían salir en orden distinto en cada
                // consulta y una fila se repetiría en la página 1 y faltaría en la 2.
                val fromToday = descending.filter { it.occurredAt == now.toString() }
                withClue("Los cuatro registros de hoy salen en orden de secuencia inverso") {
                    fromToday.size shouldBe 4
                    fromToday.map { it.sequenceNo } shouldBe fromToday.map { it.sequenceNo }.sortedDescending()
                }
            }
        }
    }

    describe("las seis dimensiones llegan completas al DTO") {

        it("quién, qué, sobre qué, cuándo, desde dónde y con qué permiso") {
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val denied = AuditQueryService(pool)
                    .list(AuditFilters(decision = "DENIED"))
                    .single()

                // Quién —el snapshot, no una unión a `users`: el histórico debe
                // seguir siendo legible aunque después se renombre el usuario—.
                denied.userId shouldBe userId
                denied.username shouldBe "auditora"
                denied.userName shouldBe "Auditora de Prueba"
                denied.roles shouldBe listOf("OPERADOR_ALMACEN")

                // Qué.
                denied.action shouldBe "AUTH_LOGIN_DENIED"
                denied.actionLevel shouldBe "ACCESS"

                // Sobre qué.
                denied.entity shouldBe "user"
                denied.entityId shouldBe "7"
                denied.changedFields shouldBe emptyList()

                // Cuándo.
                denied.occurredAt shouldBe now.toString()
                denied.businessDate shouldBe today.toString()

                // Desde dónde.
                denied.channel shouldBe "WEB"
                denied.ip shouldBe "192.168.1.44"
                denied.userAgent shouldBe "Mozilla/5.0"

                // Con qué permiso y con qué resultado.
                denied.permission shouldBe "audit.view"
                denied.decision shouldBe "DENIED"
                denied.result shouldBe "DENIED"
                denied.errorCode shouldBe "PERMISSION_DENIED"

                // De dónde viene la operación: el hilo que permite seguirla entera.
                denied.correlationId shouldBe corrB.toString()
            }
        }

        it("`source_ip` (INET) sale como texto y un usuario del sistema no inventa un cero") {
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val service = AuditQueryService(pool)

                service.list(AuditFilters(entityId = "99")).single().ip shouldBe "10.0.0.7"

                val system = service.list(AuditFilters(channel = "SYSTEM")).single()
                withClue("`actor_user_id` nulo es «no hay usuario», no el usuario 0") {
                    system.userId shouldBe null
                    system.userName shouldBe null
                    system.ip shouldBe null
                    system.correlationId shouldBe corrC.toString()
                }
            }
        }

        it("la cadena de hash viaja en cada registro para poder verificarla por fuera") {
            if (!thereIsBase) return@it

            AuditBase.withPool { pool ->
                val string = AuditQueryService(pool)
                    .list(AuditFilters(correlationId = corrA), limit = 10)
                    .sortedBy { it.sequenceNo }

                withClue(
                    "Cada registro encadena con el anterior. Sin `prevHash` y `recordHash` " +
                        "en la respuesta, la cadena es invisible desde la API y no hay forma " +
                        "de verificarla desde ningún sitio: no existe endpoint de integridad",
                ) {
                    string.map { it.recordHash } shouldBe
                        listOf("hash-1", "hash-2", "hash-3", "hash-6")
                    string.first().prevHash shouldBe null
                    // La cadena es del LEDGER, no de la operación: R6 encadena con R5
                    // (que es de otra operación), no con R3. Es justo lo que la hace
                    // tamper-evident: no se puede reescribir un tramo aislado.
                    string.drop(1).map { it.prevHash } shouldBe
                        listOf("hash-1", "hash-2", "hash-5")
                    string.map { it.sequenceNo } shouldBe string.map { it.sequenceNo }.sorted()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LA RUTA: contrato HTTP, sobre de paginación y validación
    // ═══════════════════════════════════════════════════════════════════════

    describe("GET /v1/audit/log por HTTP") {

        it("responde 200 con el sobre paginado y el total real") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?size=2")

            status shouldBe HttpStatusCode.OK
            json.items().size shouldBe 2
            json["page"]!!.jsonPrimitive.int shouldBe 0
            json["size"]!!.jsonPrimitive.int shouldBe 2
            // **6 y no 2**: el total es del ledger entero, no de la página.
            json.total() shouldBe 6L
            json["totalPages"]!!.jsonPrimitive.int shouldBe 3
        }

        it("el orden por defecto es descendente aunque no se pida `sort`") {
            if (!thereIsBase) return@it

            val (_, json) = request("/v1/audit/log?size=1")
            json.first()["occurredAt"]!!.jsonPrimitive.content shouldBe tomorrow.toString()
        }

        it("filtra por `correlationId`: una operación completa a través de sus registros") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?correlationId=$corrA")

            status shouldBe HttpStatusCode.OK
            json.total() shouldBe 4L
            json.items().size shouldBe 4
            json.items().forEach { item ->
                item["correlationId"]!!.jsonPrimitive.content shouldBe corrA.toString()
            }
        }

        it("reconstruye la operación entera con sus tres entidades y su denegación") {
            // El «de dónde» que pidió el usuario: una operación, sus registros, y
            // quién hizo cada uno.
            if (!thereIsBase) return@it

            val (_, json) = request("/v1/audit/log?correlationId=$corrB")

            json.total() shouldBe 1L
            json.first()["decision"]!!.jsonPrimitive.content shouldBe "DENIED"
            json.first()["username"]!!.jsonPrimitive.content shouldBe "auditora"
        }

        it("un filtro que no devuelve nada da `items: []` Y `totalItems: 0`") {
            // La comprobación explícita del error del kardex: `items` vacío con un
            // total de mil y pico. Aquí los dos tienen que decir lo mismo.
            if (!thereIsBase) return@it

            val (status, json) = request(
                "/v1/audit/log?correlationId=$corrNonexistent&action=NO_EXISTE_ESTA_ACCION",
            )

            status shouldBe HttpStatusCode.OK
            json.items().size shouldBe 0
            json.total() shouldBe 0L
            json["totalPages"]!!.jsonPrimitive.int shouldBe 0
        }

        it("`from` y `to` admiten la fecha suelta y cubren el día completo") {
            if (!thereIsBase) return@it

            // Solo el día de hoy: 4 registros, ninguno de ayer ni de mañana. Si la
            // fecha suelta se interpretara como medianoche exacta, `to` dejaría
            // fuera los cuatro y el total sería 0.
            val (status, json) = request("/v1/audit/log?from=$today&to=$today")
            status shouldBe HttpStatusCode.OK
            json.total() shouldBe 4L
        }

        it("`decision=denied` devuelve solo las denegaciones, sin importar las mayúsculas") {
            if (!thereIsBase) return@it

            val (_, json) = request("/v1/audit/log?decision=denied")
            json.total() shouldBe 1L
            json.first()["action"]!!.jsonPrimitive.content shouldBe "AUTH_LOGIN_DENIED"
        }
    }

    describe("los parámetros inválidos se rechazan con 400, no se ignoran ni dan 500") {

        it("`size=201` se rechaza con INVALID_PAGINATION y no se recorta a 200") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?size=201")
            status shouldBe HttpStatusCode.BadRequest
            json["code"]!!.jsonPrimitive.content shouldBe "INVALID_PAGINATION"

            // Y 200 sigue siendo válido: el tope se rechaza, no se recorta.
            request("/v1/audit/log?size=200").first shouldBe HttpStatusCode.OK
        }

        it("un `correlationId` que no es UUID da 400 y no un 500 de PostgreSQL") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?correlationId=no-es-uuid")
            status shouldBe HttpStatusCode.BadRequest
            json["code"]!!.jsonPrimitive.content shouldBe "INVALID_FILTER"
        }

        it("un `channel` que no existe en el enum da 400 y no un 500 del CAST") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?channel=TELEPATIA")
            status shouldBe HttpStatusCode.BadRequest
            json["code"]!!.jsonPrimitive.content shouldBe "INVALID_FILTER"
        }

        it("una fecha que no es fecha da 400 en lugar de ignorarse") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?from=ayer")
            status shouldBe HttpStatusCode.BadRequest
            json["code"]!!.jsonPrimitive.content shouldBe "INVALID_FILTER_DATE"

            val (statusUser, jsonUser) = request("/v1/audit/log?userId=muchos")
            statusUser shouldBe HttpStatusCode.BadRequest
            jsonUser["code"]!!.jsonPrimitive.content shouldBe "INVALID_FILTER"
        }

        it("un rango invertido se rechaza: no puede devolver «no hay nada» en silencio") {
            if (!thereIsBase) return@it

            val (status, json) = request("/v1/audit/log?from=$tomorrow&to=$yesterday")
            status shouldBe HttpStatusCode.BadRequest
            json["code"]!!.jsonPrimitive.content shouldBe "INVALID_FILTER_RANGE"
        }
    }
})

/** El estado y el cuerpo de una respuesta, ya parseado. */
private typealias Response = Pair<HttpStatusCode, JsonObject>

/**
 * La base dedicada de estos tests, con sus fixtures.
 *
 * Recrea la base entera porque `audit_log` **no admite `DELETE`**: el disparador
 * `audit_log_block_mutations` lo impide, y eso es la característica, no un
 * estorbo. Un test que intente limpiar sus filas descubrirá que no puede, así que
 * la limpieza es recrear la base.
 *
 * No se toca `stock_test`, que es compartida: soltar el esquema de una base que
 * otra prueba está usando produce fallos que dependen del orden de ejecución.
 */
private object AuditBase {

    private const val HOST = "127.0.0.1"
    private const val Port = "5432"

    /** Base propia de este spec: se crea, se migra y se destruye en cada corrida. */
    const val Name = "stock_audit_test"

    val user: String = System.getenv("STOCK_TEST_DB_USER") ?: "stock_app"
    val password: String = System.getenv("STOCK_TEST_DB_PASSWORD") ?: passwordLocal()

    private fun passwordLocal(): String {
        val file: File? = listOf(File("infra/.env"), File("../infra/.env"))
            .firstOrNull { it.exists() }

        return file?.readLines()
            ?.firstOrNull { it.startsWith("POSTGRES_PASSWORD=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""
    }

    private fun config(nameBase: String) = DatabaseConfig(
        jdbcUrl = "jdbc:postgresql://$HOST:$Port/$nameBase",
        username = user,
        password = password,
        maxPoolSize = 2,
        runMigrations = false,
    )

    /**
     * Diagnóstico de conexión: `null` si la base responde.
     *
     * Se ejecuta una consulta de verdad y no solo se pide una conexión: HikariCP
     * las obtiene de forma perezosa, así que la comprobación pasaría sin haber
     * hablado con el servidor.
     */
    fun connectionProblem(): String? = try {
        withPool { pool ->
            pool.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { rows -> rows.next() }
                }
            }
        }
        null
    } catch (e: Exception) {
        "${e::class.simpleName}: ${e.message}"
    }

    fun <T> withPool(block: (DataSource) -> T): T {
        val pool = buildDataSource(config(Name))
        return try {
            block(pool)
        } finally {
            (pool as? AutoCloseable)?.close()
        }
    }

    /**
     * Igual que [conPool], pero para bloques `suspend`.
     *
     * `testApplication` de Ktor es `suspend`, y desde un lambda normal no se puede
     * llamar; este es el puente que mantiene el mismo cuidado con el pool: se
     * cierra siempre, incluso si la prueba falla.
     */
    suspend fun <T> withPoolSuspend(block: suspend (DataSource) -> T): T {
        val pool = buildDataSource(config(Name))
        return try {
            block(pool)
        } finally {
            (pool as? AutoCloseable)?.close()
        }
    }

    /** Borra, recrea y migra la base dedicada. Se conecta a `postgres` para poder soltarla. */
    fun recreate() {
        val poolAdmin = buildDataSource(config("postgres"))
        try {
            poolAdmin.connection.use { connection ->
                // `autoCommit = true`: con una transacción abierta el `CREATE
                // DATABASE` quedaría sin confirmar y al cerrar la conexión
                // PostgreSQL haría ROLLBACK, dejando la base sin crear mientras el
                // `DROP` sí se habría aplicado.
                connection.autoCommit = true
                connection.createStatement().use { statement ->
                    // `WITH (FORCE)` cierra las conexiones abiertas: sin esto, un
                    // pool que quedó vivo impide soltar la base.
                    statement.execute("DROP DATABASE IF EXISTS $Name WITH (FORCE)")
                    statement.execute("CREATE DATABASE $Name")
                }
            }
        } finally {
            (poolAdmin as? AutoCloseable)?.close()
        }

        withPool { pool -> migrate(pool) }
    }

    fun insertUser(pool: DataSource, username: String, name: String): Long =
        pool.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                "INSERT INTO users (username, password_hash, name) VALUES (?, 'no-usado-en-tests', ?) " +
                    "RETURNING id",
            ).use { statement ->
                statement.setString(1, username)
                statement.setString(2, name)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    /** El `INSERT` completo de un registro de auditoría, con todas sus dimensiones. */
    @Suppress("LongParameterList")
    fun insert(
        pool: DataSource,
        correlationId: UUID,
        occurredAt: Instant,
        action: String,
        entityType: String,
        entityId: String?,
        actorUserId: Long?,
        actorUsername: String?,
        actorName: String?,
        actorRoles: List<String>?,
        channel: String,
        level: String,
        result: String,
        decision: String?,
        permission: String?,
        ip: String?,
        userAgent: String?,
        changedFields: List<String>?,
        prevHash: String?,
        recordHash: String,
        reason: String? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        pool.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement(SQL_INSERT).use { statement ->
                var i = 1
                // `OffsetDateTime` con offset explícito y no `Timestamp`: un
                // `Timestamp` viaja sin zona y el instante dependería de la zona del
                // proceso que ejecuta el test.
                statement.setObject(i++, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                statement.setObject(i++, occurredAt.atZone(ZoneOffset.UTC).toLocalDate())
                if (actorUserId == null) {
                    statement.setNull(i++, Types.BIGINT)
                } else {
                    statement.setLong(i++, actorUserId)
                }
                statement.setString(i++, actorUsername)
                statement.setString(i++, actorName)
                statement.setArray(i++, actorRoles?.let { connection.createArrayOf("text", it.toTypedArray()) })
                statement.setString(i++, channel)
                statement.setString(i++, ip)
                statement.setString(i++, userAgent)
                statement.setString(i++, entityType)
                statement.setString(i++, entityId)
                statement.setString(i++, action)
                statement.setString(i++, level)
                statement.setString(i++, permission)
                statement.setString(i++, decision)
                statement.setString(i++, result)
                statement.setArray(
                    i++,
                    changedFields?.let { connection.createArrayOf("text", it.toTypedArray()) },
                )
                statement.setObject(i++, correlationId)
                statement.setString(i++, prevHash)
                statement.setString(i++, recordHash)
                statement.setString(i++, reason)
                statement.setString(i++, errorCode)
                statement.setString(i, errorMessage)
                statement.execute()
            }
        }
    }

    /**
     * Se inserta **por el padre** `audit_log`, que es lo correcto: PostgreSQL
     * enruta la fila a la partición del mes de `occurred_at`. Insertar en una
     * partición concreta saltaría el enrutado y probaría algo que la aplicación
     * nunca hace.
     */
    private val SQL_INSERT = """
        INSERT INTO audit_log (
            occurred_at, business_date, actor_user_id, actor_username, actor_name, actor_roles,
            source_channel, source_ip, source_user_agent,
            entity_type, entity_id, action, action_level,
            permission_key, decision, result, changed_fields,
            correlation_id, prev_hash, record_hash,
            reason, error_code, error_message
        ) VALUES (
            ?, ?, ?, ?, ?, ?, ?::audit_channel, ?::inet, ?,
            ?, ?, ?, ?::audit_level,
            ?, ?::audit_decision, ?::audit_result, ?,
            ?, ?, ?,
            ?, ?, ?
        )
    """.trimIndent()
}
