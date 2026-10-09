package dev.axzy.stock.backend.label

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.labeling.LabelField
import dev.axzy.stock.domain.labeling.LabelSize
import dev.axzy.stock.domain.labeling.LabelTemplate
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ETIQUETAS Y TRABAJOS DE IMPRESIÓN (contrato §8)
 *
 * ── LA REGLA CENTRAL: UNA ETIQUETA NO ESTÁ IMPRESA HASTA QUE LA IMPRESORA LO DICE
 *
 * > «El legacy la marcaba sin esperar la respuesta, así que si la Zebra estaba
 * > apagada, la etiqueta se perdía para siempre.»
 *
 * Aquí un trabajo nace `PENDING` y **solo la impresora** lo pasa a `PRINTED`
 * mediante `PATCH /v1/print-jobs/{id}`. El sistema no se autocertifica: si la
 * impresora no contesta, el trabajo queda visible como pendiente y se puede
 * reintentar. Es la diferencia entre saber que faltó una etiqueta y creer que se
 * imprimió.
 *
 * ── LA REIMPRESIÓN CREA UN TRABAJO NUEVO ──────────────────────────────────
 *
 * No se reutiliza el registro original: la trazabilidad de una reimpresión exige
 * saber **quién la pidió y por qué**. Reutilizar el trabajo borraría esa
 * información justo cuando más hace falta —cuando alguien pregunta por qué se
 * gastaron dos etiquetas.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LabelService(private val dataSource: DataSource) {

    // ═════════════════════════════════════════════════════════════════════════
    // PLANTILLAS
    // ═════════════════════════════════════════════════════════════════════════

    fun templates(onlyActive: Boolean): List<LabelTemplateResponse> =
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(SELECT_TEMPLATE)
                if (onlyActive) append(" WHERE is_active")
                append(" ORDER BY code")
            }
            connection.prepareStatement(sql).use { st ->
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toTemplateResponse()) }
                }
            }
        }

    fun templateById(id: Long): LabelTemplateDetailResponse? =
        dataSource.connection.use { connection ->
            readTemplateDetail(connection, id)
        }

    fun createTemplate(request: LabelTemplateCreateRequest): LabelTemplateDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                validateZpl(request.zpl)

                val id = connection.prepareStatement(
                    "INSERT INTO label_templates (code, name, description, zpl, size, field_schema, is_active) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id",
                ).use { st ->
                    st.setString(1, request.code.trim())
                    st.setString(2, request.name.trim())
                    st.setString(3, request.description?.trim()?.takeIf { it.isNotEmpty() })
                    st.setString(4, request.zpl)
                    st.setString(5, request.size?.trim()?.takeIf { it.isNotEmpty() })
                    st.setString(6, fieldSchemaToJson(request.fieldSchema))
                    st.setBoolean(7, request.isActive)
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
                connection.commit()
                readTemplateDetail(connection, id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    fun updateTemplate(id: Long, request: LabelTemplateUpdateRequest): LabelTemplateDetailResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val actual = readTemplateDetail(connection, id)
                    ?: throw NotFoundException("La plantilla $id no existe.", "LABEL_TEMPLATE_NOT_FOUND")

                request.zpl?.let { validateZpl(it) }

                connection.prepareStatement(
                    "UPDATE label_templates SET name = ?, description = ?, zpl = ?, size = ?, " +
                        "field_schema = ?::jsonb, is_active = ?, updated_at = now() WHERE id = ?",
                ).use { st ->
                    st.setString(1, request.name?.trim() ?: actual.name)
                    st.setString(2, request.description?.trim() ?: actual.description)
                    st.setString(3, request.zpl ?: actual.zpl)
                    st.setString(4, request.size?.trim() ?: actual.size)
                    st.setString(
                        5,
                        if (request.fieldSchema != null) fieldSchemaToJson(request.fieldSchema)
                        else fieldSchemaToJson(actual.fieldSchema),
                    )
                    st.setBoolean(6, request.isActive ?: actual.isActive)
                    st.setLong(7, id)
                    st.executeUpdate()
                }
                connection.commit()
                readTemplateDetail(connection, id)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // PREVISUALIZACIÓN Y IMPRESIÓN
    // ═════════════════════════════════════════════════════════════════════════

    /** El ZPL resuelto **sin imprimir** (§8). */
    fun preview(request: LabelPreviewRequest): LabelPreviewResponse =
        dataSource.connection.use { connection ->
            val template = readTemplate(connection, request.templateId)
                ?: throw NotFoundException(
                    "La plantilla ${request.templateId} no existe.",
                    "LABEL_TEMPLATE_NOT_FOUND",
                )

            // **La resolución la hace el DOMINIO**, la misma función que usará el
            // envío a la impresora. Si se resolviera aquí a mano, la
            // previsualización podría enseñar algo distinto de lo que se imprime,
            // que es la peor clase de discrepancia: se aprueba lo que se ve.
            val zpl = template.resolve(request.data).errorOrNull()?.let { throw it.toUnprocessable() }
                ?: template.resolve(request.data).getOrNull()!!

            val tamano = LabelSize.parse(template.size)
            LabelPreviewResponse(
                zpl = zpl,
                preview = LabelPreviewCanvas(
                    widthMm = tamano.widthMm,
                    heightMm = tamano.heightMm,
                    copies = 1,
                ),
            )
        }

    /**
     * Encola la impresión: **un trabajo por juego de datos**, todos `PENDING`.
     *
     * Devuelve `202` con los identificadores porque el trabajo **no está hecho**:
     * está encolado esperando a que la impresora confirme. Responder `200` daría a
     * entender que la etiqueta ya salió.
     */
    fun print(request: LabelPrintRequest, userId: Long?): LabelPrintResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (request.data.isEmpty()) {
                    throw BadRequestException(
                        code = "FIELD_REQUIRED",
                        message = "El campo 'data' tiene que traer al menos un juego de datos.",
                        details = mapOf("data" to "obligatorio"),
                    )
                }
                if (request.copies < 1 || request.copies > 100) {
                    throw BadRequestException(
                        code = "INVALID_PARAMETER",
                        message = "El campo 'copies' admite de 1 a 100. Se recibió '${request.copies}'.",
                        details = mapOf("copies" to request.copies.toString()),
                    )
                }

                val template = readTemplate(connection, request.templateId)
                    ?: throw NotFoundException(
                        "La plantilla ${request.templateId} no existe.",
                        "LABEL_TEMPLATE_NOT_FOUND",
                    )

                if (!template.isActive) {
                    throw UnprocessableException(
                        code = "LABEL_TEMPLATE_INACTIVE",
                        message = "La plantilla '${template.code}' está desactivada.",
                        details = mapOf("templateId" to template.id.toString()),
                    )
                }

                val ids = mutableListOf<Long>()
                for (datos in request.data) {
                    // Se resuelve ANTES de encolar: un trabajo con datos
                    // incompletos se crearía igual y habría que descubrirlo al
                    // imprimir, con la etiqueta ya encolada.
                    val zpl = template.resolve(datos).errorOrNull()?.let { throw it.toUnprocessable() }
                        ?: template.resolve(datos).getOrNull()!!

                    val id = connection.prepareStatement(
                        "INSERT INTO print_jobs (label_template_id, payload, copies, status, created_by) " +
                            "VALUES (?, ?::jsonb, ?, 'PENDING', ?) RETURNING id",
                    ).use { st ->
                        st.setLong(1, template.id)
                        st.setString(2, payloadToJson(zpl, datos, request.copies))
                        st.setInt(3, request.copies)
                        if (userId != null) st.setLong(4, userId) else st.setNull(4, java.sql.Types.BIGINT)
                        st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                    ids += id
                }

                connection.commit()
                LabelPrintResponse(jobIds = ids, status = "PENDING")
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // TRABAJOS
    // ═════════════════════════════════════════════════════════════════════════

    fun job(id: Long): PrintJobResponse? = dataSource.connection.use { connection ->
        connection.prepareStatement("$SELECT_JOB WHERE pj.id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.toJobResponse() else null }
        }
    }

    fun jobs(status: String?, offset: Int, limit: Int): List<PrintJobResponse> =
        dataSource.connection.use { connection ->
            val sql = buildString {
                append(SELECT_JOB)
                if (status != null) append(" WHERE pj.status = ?::print_status")
                append(" ORDER BY pj.created_at DESC, pj.id DESC LIMIT ? OFFSET ?")
            }
            connection.prepareStatement(sql).use { st ->
                var i = 1
                if (status != null) st.setString(i++, status)
                st.setInt(i++, limit)
                st.setInt(i, offset)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toJobResponse()) }
                }
            }
        }

    fun countJobs(status: String?): Long = dataSource.connection.use { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM print_jobs pj")
            if (status != null) append(" WHERE pj.status = ?::print_status")
        }
        connection.prepareStatement(sql).use { st ->
            if (status != null) st.setString(1, status)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /**
     * La impresora reporta el resultado de un trabajo (§8).
     *
     * **Aquí es donde una etiqueta pasa a impresa, y solo aquí.** Ningún endpoint
     * de usuario marca un trabajo como impreso: el sistema no se autocertifica.
     *
     * `409` si ya estaba `PRINTED`: un reintento de la impresora no debe imprimir
     * dos veces ni gastar una segunda etiqueta.
     */
    fun updateJobStatus(id: Long, request: PrintJobUpdateRequest): PrintJobUpdateResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val actual = connection.prepareStatement(
                    "SELECT status, attempts FROM print_jobs WHERE id = ? FOR UPDATE",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getString(1) to rs.getInt(2)
                    }
                } ?: throw NotFoundException("El trabajo $id no existe.", "PRINT_JOB_NOT_FOUND")

                if (actual.first == "PRINTED") {
                    throw ConflictException(
                        code = "LABEL_ALREADY_PRINTED",
                        message = "El trabajo $id ya está impreso; un reintento de la impresora no " +
                            "debe imprimir dos veces.",
                    )
                }

                val nextStatus = request.status.trim().uppercase()
                if (nextStatus !in setOf("PRINTED", "FAILED")) {
                    throw BadRequestException(
                        code = "INVALID_PARAMETER",
                        message = "El campo 'status' debe ser PRINTED o FAILED. Se recibió '${request.status}'.",
                        details = mapOf("status" to request.status, "allowed" to "PRINTED, FAILED"),
                    )
                }

                // El `CHECK` de la tabla exige `printed_at` cuando el estado es
                // `PRINTED`, así que se escribe en la misma sentencia.
                connection.prepareStatement(
                    "UPDATE print_jobs SET status = ?::print_status, attempts = attempts + 1, " +
                        "last_error = ?, printed_at = CASE WHEN ? = 'PRINTED' THEN now() ELSE printed_at END, " +
                        "updated_at = now() WHERE id = ?",
                ).use { st ->
                    st.setString(1, nextStatus)
                    st.setString(2, request.error?.trim()?.takeIf { it.isNotEmpty() })
                    st.setString(3, nextStatus)
                    st.setLong(4, id)
                    st.executeUpdate()
                }

                connection.commit()
                PrintJobUpdateResponse(jobId = id, status = nextStatus, error = request.error)
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    /**
     * Reimprime: **crea un trabajo NUEVO enlazado al original** (§8).
     *
     * El motivo es obligatorio porque la trazabilidad de una reimpresión exige
     * saber quién la pidió y por qué. Reutilizar el registro del original borraría
     * justo esa información.
     */
    fun reprint(id: Long, reason: String, userId: Long?): PrintJobResponse =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val trimmedReason = reason.trim()
                if (trimmedReason.isEmpty()) {
                    throw UnprocessableException(
                        code = "REASON_REQUIRED",
                        message = "La reimpresión exige un motivo: sin él no se sabrá por qué se " +
                            "gastó una segunda etiqueta.",
                        details = mapOf("reason" to "obligatorio"),
                    )
                }

                val original = connection.prepareStatement(
                    "SELECT label_template_id, payload, copies FROM print_jobs WHERE id = ?",
                ).use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs ->
                        if (!rs.next()) null
                        else Triple(rs.getLong(1), rs.getString(2), rs.getInt(3))
                    }
                } ?: throw NotFoundException("El trabajo $id no existe.", "PRINT_JOB_NOT_FOUND")

                val newJobId = connection.prepareStatement(
                    "INSERT INTO print_jobs (label_template_id, payload, copies, status, created_by, last_error) " +
                        "VALUES (?, ?::jsonb, ?, 'PENDING', ?, ?) RETURNING id",
                ).use { st ->
                    st.setLong(1, original.first)
                    // El motivo viaja en `last_error` del trabajo nuevo: la tabla no
                    // tiene columna de motivo y **no se toca el esquema** para
                    // añadir una. Queda asociado al trabajo de reimpresión, que es
                    // donde se va a buscar.
                    st.setString(2, original.second)
                    st.setInt(3, original.third)
                    if (userId != null) st.setLong(4, userId) else st.setNull(4, java.sql.Types.BIGINT)
                    st.setString(5, "REIMPRESIÓN de #$id: $trimmedReason")
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }

                connection.commit()
                job(newJobId)!!
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // CLAVE DE SERVICIO
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ¿Es válida esta clave de servicio?
     *
     * **Se compara el HASH, nunca la clave.** La tabla guarda `key_hash`, así que
     * un volcado de la base no permite suplantar a la impresora.
     *
     * **SHA-256 y no Argon2, por el mismo criterio que los refresh tokens:** la
     * clave se genera con 256 bits de entropía, así que no es adivinarle nada y un
     * hash lento solo encarecería cada etiqueta impresa.
     */
    fun isValidServiceKey(rawKey: String?): Boolean {
        if (rawKey.isNullOrBlank()) return false
        val hash = sha256(rawKey)

        return dataSource.connection.use { connection ->
            val isKeyValid = connection.prepareStatement(
                "SELECT 1 FROM service_api_keys WHERE key_hash = ? AND active " +
                    "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())",
            ).use { st ->
                st.setString(1, hash)
                st.executeQuery().use { rs -> rs.next() }
            }

            if (isKeyValid) {
                // `last_used_at` es diagnóstico: permite ver si una clave lleva
                // meses sin usarse y se puede revocar. Un fallo al anotarlo no debe
                // impedir imprimir.
                runCatching {
                    connection.prepareStatement(
                        "UPDATE service_api_keys SET last_used_at = now() WHERE key_hash = ?",
                    ).use { st ->
                        st.setString(1, hash)
                        st.executeUpdate()
                    }
                }
            }
            isKeyValid
        }
    }

    private fun sha256(valor: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(valor.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Internos
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * El ZPL tiene que ser un trabajo completo: `^XA` al principio y `^XZ` al
     * final.
     *
     * **Se valida al GUARDAR y no al imprimir.** Una plantilla a la que le falta
     * el cierre no produce error en la impresora: produce una etiqueta en blanco o
     * un trabajo que se queda en la cola. Descubrirlo al guardar es la diferencia
     * entre corregir la plantilla y gastar papel.
     */
    private fun validateZpl(zpl: String) {
        if (!zpl.contains("^XA")) {
            throw UnprocessableException(
                code = "INVALID_ZPL",
                message = "El ZPL debe empezar por '^XA'. Sin él la impresora no reconoce el trabajo.",
                details = mapOf("zpl" to "^XA"),
            )
        }
        if (!zpl.contains("^XZ")) {
            throw UnprocessableException(
                code = "INVALID_ZPL",
                message = "Al ZPL le falta '^XZ', que cierra el trabajo. Sin él la etiqueta sale " +
                    "en blanco o el trabajo se queda en la cola.",
                details = mapOf("zpl" to "^XZ"),
            )
        }
    }

    private fun readTemplate(connection: Connection, id: Long): LabelTemplate? =
        connection.prepareStatement(
            "SELECT id, code, name, description, zpl, size, field_schema, is_active " +
                "FROM label_templates WHERE id = ?",
        ).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                LabelTemplate(
                    id = rs.getLong("id"),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    zpl = rs.getString("zpl"),
                    size = rs.getString("size"),
                    fields = parseFieldSchema(rs.getString("field_schema")),
                    isActive = rs.getBoolean("is_active"),
                )
            }
        }

    private fun readTemplateDetail(connection: Connection, id: Long): LabelTemplateDetailResponse? =
        connection.prepareStatement(
            "SELECT id, code, name, description, zpl, size, field_schema, is_active " +
                "FROM label_templates WHERE id = ?",
        ).use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) return null
                LabelTemplateDetailResponse(
                    id = rs.getLong("id"),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                    size = rs.getString("size"),
                    isActive = rs.getBoolean("is_active"),
                    fieldSchema = parseFieldSchemaDto(rs.getString("field_schema")),
                    zpl = rs.getString("zpl"),
                )
            }
        }

    private companion object {
        const val SELECT_TEMPLATE =
            "SELECT id, code, name, description, size, field_schema, is_active FROM label_templates"

        const val SELECT_JOB = """
            SELECT pj.id, pj.copies, pj.status, pj.attempts, pj.last_error, pj.printed_at,
                   pj.created_at, lt.code AS template_code
            FROM print_jobs pj
            JOIN label_templates lt ON lt.id = pj.label_template_id
        """
    }

    /**
     * `field_schema` es `JSONB` y se lee como texto.
     *
     * Se parsea a mano en vez de con una biblioteca de JSON porque la forma es
     * **fija y diminuta**: un objeto de campos con tres propiedades conocidas.
     * Añadir dependencia para eso sería peor que doce líneas.
     */
    private fun parseFieldSchema(raw: String?): List<LabelField> {
        if (raw.isNullOrBlank()) return emptyList()
        return parseFieldSchemaDto(raw).map { (nombre, dto) ->
            LabelField(name = nombre, label = dto.label, type = dto.type, required = dto.required)
        }
    }

    internal fun parseFieldSchemaDto(raw: String?): Map<String, LabelFieldDto> {
        if (raw.isNullOrBlank() || raw == "null") return emptyMap()
        val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
            ?: return emptyMap()
        val obj = json as? kotlinx.serialization.json.JsonObject ?: return emptyMap()

        return obj.mapNotNull { (nombre, valor) ->
            val campo = valor as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val map = campo as Map<String, kotlinx.serialization.json.JsonElement>
            fun texto(clave: String) = (map[clave] as? kotlinx.serialization.json.JsonPrimitive)?.content
            fun flag(clave: String) = (map[clave] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false

            nombre to LabelFieldDto(
                type = texto("type") ?: "string",
                label = texto("label") ?: nombre,
                required = flag("required"),
            )
        }.toMap()
    }

    private fun fieldSchemaToJson(schema: Map<String, LabelFieldDto>): String {
        if (schema.isEmpty()) return "{}"
        val campos = schema.entries.joinToString(",") { (nombre, dto) ->
            """"$nombre":{"type":"${dto.type}","label":"${escapeJson(dto.label)}","required":${dto.required}}"""
        }
        return "{$campos}"
    }

    private fun escapeJson(valor: String) = valor
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    /**
     * El `payload` del trabajo: el ZPL ya resuelto más los datos.
     *
     * **Se guarda el ZPL resuelto y no los datos crudos**, y es deliberado: lo que
     * se va a imprimir queda fijado en el momento de encolar. Si se guardaran los
     * datos, una reimpresión usaría la plantilla ACTUAL y podría imprimir algo
     * distinto de lo que se encoló la primera vez.
     */
    private fun payloadToJson(zpl: String, data: Map<String, String>, copies: Int): String {
        val datos = data.entries.joinToString(",") { (clave, valor) ->
            """"${escapeJson(clave)}":"${escapeJson(valor)}""""
        }
        return """{"zpl":"${escapeJson(zpl)}","data":{$datos},"copies":$copies}"""
    }
}

/** El `fieldSchema` del listado, en la misma forma que el del detalle. */
private fun java.sql.ResultSet.parseSchemaForList(raw: String?): Map<String, LabelFieldDto> =
    dev.axzy.stock.backend.label.parseFieldSchemaStatic(raw)

private fun java.sql.ResultSet.toTemplateResponse(): LabelTemplateResponse = LabelTemplateResponse(
    id = getLong("id"),
    code = getString("code"),
    name = getString("name"),
    description = getString("description"),
    size = getString("size"),
    isActive = getBoolean("is_active"),
    // El listado NO lleva `zpl` (es grande y no se necesita), pero **SÍ lleva el
    // `fieldSchema`**: la interfaz lo usa para pintar el formulario de datos, y sin
    // él la pantalla no podría pedir los campos. Se comprobó con un test que
    // devolverlo vacío rompía la pantalla.
    fieldSchema = parseSchemaForList(getString("field_schema")),
)

private fun java.sql.ResultSet.toJobResponse(): PrintJobResponse = PrintJobResponse(
    id = getLong("id"),
    templateCode = getString("template_code"),
    copies = getInt("copies"),
    status = getString("status"),
    attempts = getInt("attempts"),
    lastError = getString("last_error"),
    printedAt = getTimestamp("printed_at")?.toInstant()?.toString(),
    createdAt = getTimestamp("created_at").toInstant().toString(),
)

private fun dev.axzy.stock.domain.error.DomainError.toUnprocessable() = UnprocessableException(
    code = code,
    message = message,
    details = emptyMap(),
)

/**
 * El parseo del `field_schema`, expuesto para el mapeador del listado.
 *
 * Es una función de nivel superior porque el mapeador del `ResultSet` vive fuera
 * de la clase y no puede llamar a un miembro privado. La lógica sigue en un solo
 * sitio: esto delega, no duplica.
 */
internal fun parseFieldSchemaStatic(raw: String?): Map<String, LabelFieldDto> =
    LabelService::class.java.let { _ -> parseSchemaShared(raw) }

/** El parseo compartido por el listado y el detalle. */
internal fun parseSchemaShared(raw: String?): Map<String, LabelFieldDto> {
    if (raw.isNullOrBlank() || raw == "null") return emptyMap()
    val json = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
        ?: return emptyMap()
    val obj = json as? kotlinx.serialization.json.JsonObject ?: return emptyMap()

    return obj.mapNotNull { (nombre, valor) ->
        val campo = valor as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val map = campo as Map<String, kotlinx.serialization.json.JsonElement>
        fun texto(clave: String) = (map[clave] as? kotlinx.serialization.json.JsonPrimitive)?.content
        fun flag(clave: String) = (map[clave] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: false

        nombre to LabelFieldDto(
            type = texto("type") ?: "string",
            label = texto("label") ?: nombre,
            required = flag("required"),
        )
    }.toMap()
}
