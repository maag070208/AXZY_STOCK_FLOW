package dev.axzy.stock.domain.migration

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * GUARDIAS DE LAS MIGRACIONES SQL
 *
 * Este test corre en JVM y lee los archivos `.sql` del repositorio. No se
 * conecta a PostgreSQL: eso lo hace el workflow de CI, que además aplica las
 * migraciones desde cero contra una base real.
 *
 * **¿Por qué un test que lee SQL en lugar de probarlo contra una base?**
 * Porque hay errores que se detectan sin base de datos y que es importante
 * detectar **en cada commit**, no solo en CI: que las migraciones estén
 * versionadas de forma correlativa, que las inmutabilidades del ledger sigan
 * declaradas, y que nadie haya colado un `DROP` o un `DELETE` en una migración.
 *
 * La división es deliberada: aquí se verifica la **forma** (barato y en cada
 * commit), en CI se verifica la **ejecución** (caro y contra Postgres real).
 *
 * La lección del legacy: tenía 36 migraciones en una carpeta que Prisma no leía
 * más 3 en la carpeta correcta, y una de ellas recreaba todo el esquema. Su
 * historial **no reconstruía la base**, así que un despliegue limpio era
 * imposible. Estas guardias son la corrección.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class SqlMigrationGuardTest : DescribeSpec({

    /**
     * Localiza el directorio de migraciones subiendo desde el directorio de
     * trabajo, para que el test funcione tanto desde la raíz del monorepo como
     * desde el directorio del módulo.
     */
    fun migrationsDir(): File {
        val candidates = listOf(
            File("db/migrations"),
            File("../db/migrations"),
            File("../../db/migrations"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "No se encontró el directorio de migraciones. Buscado en: " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    fun filesSql(): List<File> = migrationsDir()
        .listFiles { f -> f.extension == "sql" }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun contentComplete(): String = filesSql().joinToString("\n") { it.readText() }

    describe("versioning of the migrations") {

        it("migrations exist and follow the convention V<n>__<name>.sql") {
            val files = filesSql()

            withClue("Debe haber al menos una migración") {
                (files.isNotEmpty()) shouldBe true
            }

            val pattern = Regex("^V\\d+__[a-z0-9_]+\\.sql$")
            files.forEach { file ->
                withClue(
                    "La migración '${file.name}' no sigue la convención " +
                        "V<n>__<nombre>.sql que exige Flyway. Un nombre mal formado " +
                        "hace que la migración NO se aplique y falle en silencio."
                ) {
                    pattern.matches(file.name) shouldBe true
                }
            }
        }

        it("the versions are consecutive from 1, with no gaps or duplicates") {
            val versions = filesSql()
                .map { it.name.substringAfter('V').substringBefore("__").toInt() }
                .sorted()

            withClue("Versiones encontradas: $versions") {
                versions shouldBe (1..versions.size).toList()
            }
        }
    }

    describe("security guards of the schema") {

        it("the domain audit CHECKs are still declared") {
            // Cada uno de estos impone una regla de negocio en la BASE DE DATOS,
            // no solo en el código. Borrarlos convertiría una invariante en una
            // convención que se puede olvidar.
            val sql = contentComplete()

            val checksExpected = mapOf(
                "audit_critical_has_permission" to
                    "Un evento crítico sin permiso no puede responder 'con qué permiso se hizo'",
                "audit_critical_has_trace" to
                    "Un evento crítico debe guardar la traza, no solo el resultado",
                "audit_adjustment_has_reason" to
                    "Todo ajuste de inventario exige motivo (invariante I6)",
                "audit_human_has_actor" to
                    "Si lo hizo una persona, debe estar identificada",
            )

            checksExpected.forEach { (name, reason) ->
                withClue("Falta el CHECK '$name': $reason") {
                    sql shouldContain name
                }
            }
        }

        it("the immutability of the audit log applies PER PARTITION") {
            // Éste es el detalle que se descubrió verificando contra PostgreSQL:
            // un trigger declarado en una tabla particionada NO se aplica a sus
            // particiones. Ponerlo solo en el padre daba una falsa sensación de
            // protección, y un UPDATE sobre una partición PASABA.
            val sql = contentComplete()

            withClue("Debe existir la función que aplica la inmutabilidad por partición") {
                sql shouldContain "audit_log_attach_immutability"
            }
            withClue("La función de particiones debe invocarla al crear cada partición") {
                sql shouldContain "PERFORM audit_log_attach_immutability(nombre)"
            }
            withClue("La partición por defecto también debe recibirla") {
                sql shouldContain "audit_log_attach_immutability('audit_log_default')"
            }
        }

        it("the audit log blocks UPDATE and DELETE with a message that explains why") {
            val sql = contentComplete()

            withClue("Debe existir la función que bloquea las mutaciones") {
                sql shouldContain "audit_log_block_mutations"
            }
            withClue("El mensaje debe explicar la razón, no solo fallar") {
                sql shouldContain "append-only"
                sql shouldContain "La auditoría que se puede editar no es auditoría"
            }
        }

        it("the ledger uses a SHARED SEQUENCE, not IDENTITY per partition") {
            // El GENERATED ALWAYS AS IDENTITY del padre no se aplica a las
            // particiones: toda inserción fallaba con 'null value in column id'.
            // Una secuencia compartida garantiza además un orden GLOBAL del
            // ledger, no uno por partición.
            val sql = contentComplete()

            withClue("Debe existir la secuencia compartida") {
                sql shouldContain "CREATE SEQUENCE audit_log_id_seq"
            }
            withClue("La tabla debe usarla como valor por defecto") {
                sql shouldContain "DEFAULT nextval('audit_log_id_seq')"
            }
            withClue(
                "No debe usarse IDENTITY: no se aplica a las particiones y las " +
                    "inserciones fallarían"
            ) {
                (sql.contains("ADD GENERATED ALWAYS AS IDENTITY")) shouldBe false
            }
        }

        it("the permission matrix links role, permission and scope, and does not grant NONE") {
            val sql = contentComplete()

            withClue("Debe existir la tabla de la matriz") {
                sql shouldContain "CREATE TABLE role_permissions"
            }
            withClue("El alcance NONE no se concede: lo ausente es NONE") {
                sql shouldContain "role_permissions_scope_granted"
            }
            withClue("Un permiso no puede listar NONE entre sus alcances admitidos") {
                sql shouldContain "permissions_none_not_grantable"
            }
        }

        it("the ledger is append-only and protects its integrity") {
            // El ledger es el libro de inventario. En el legacy,
            // `removeUnitsFromKardex` hacía `kardex.update`: un registro
            // histórico se modificaba y la trazabilidad se destruía.
            val sql = contentComplete()

            withClue("Debe existir la función que bloquea las mutaciones del kardex") {
                sql shouldContain "kardex_block_mutations"
            }
            withClue("Y los triggers de UPDATE y DELETE") {
                sql shouldContain "kardex_no_update"
                sql shouldContain "kardex_no_delete"
            }
            withClue("Todo ajuste del kardex debe exigir motivo (invariante I6)") {
                sql shouldContain "kardex_adjustment_requires_reason"
            }
            withClue("Ubicar exige origen y destino distintos: si no, no es un traslado") {
                sql shouldContain "kardex_locate_requires_locations"
            }
            withClue("La idempotencia del móvil se protege con un índice único") {
                sql shouldContain "kardex_client_uuid_idx"
            }
        }

        it("`difference` is a GENERATED COLUMN, not a field that can get out of sync") {
            // El legacy declaraba `difference` en modelo, DTO, mapper y
            // respuesta, y siempre escribía 0 (hallazgo R-58).
            val sql = contentComplete()

            withClue("La columna debe ser GENERATED ALWAYS AS ... STORED") {
                sql shouldContain "GENERATED ALWAYS AS (physical_quantity - quantity) STORED"
            }
        }

        it("stock per location and the total cannot be negative (invariant I2)") {
            val sql = contentComplete()

            withClue("La proyección por ubicación tiene CHECK") {
                sql shouldContain "stock_non_negative"
            }
            withClue("Y el total por producto también") {
                sql shouldContain "product_stock_non_negative"
            }
        }

        it("an invoice line does not reach APPROVED without an approver or without a reason") {
            // Es la regla B1 y la segregación de funciones (R-74) impuestas en
            // la base de datos: sin esto, el control interno depende de que el
            // código se acuerde de validarlo.
            val sql = contentComplete()

            withClue("Debe exigirse aprobador") {
                sql shouldContain "invoice_lines_approved_has_approver"
            }
            withClue("Y motivo del ajuste") {
                sql shouldContain "invoice_lines_approved_has_reason"
            }
        }

        it("deleting documents leaves a trace: nothing is deleted without a reason") {
            // El legacy declaraba `deleted` en el esquema y hacía DELETE físico,
            // sin escribir nunca el flag (hallazgo B-1 / R-57).
            val sql = contentComplete()

            withClue("La factura exige motivo y fecha si está borrada") {
                sql shouldContain "invoices_deleted_coherent"
            }
            withClue("La orden también") {
                sql shouldContain "orders_deleted_coherent"
            }
        }

        it("a label is not marked printed until the printer confirms") {
            // El legacy marcaba `printed = true` sin esperar la respuesta del
            // POST: si la Zebra estaba apagada, la etiqueta se perdía para
            // siempre (hallazgo R-83).
            val sql = contentComplete()

            withClue("Debe existir la máquina de estados de impresión") {
                sql shouldContain "CREATE TYPE print_status"
            }
            withClue("Una etiqueta PRINTED debe tener la hora de confirmación") {
                sql shouldContain "print_jobs_printed_has_timestamp"
            }
            withClue("El payload debe tener esquema validable") {
                sql shouldContain "field_schema"
            }
        }

        it("the conversion to the ERP has a status and not only a boolean") {
            // El flujo de conversión NUNCA existió en el legacy: `converted` y
            // `sent` estaban y no se escribían ni se leían (hallazgo R-82).
            val sql = contentComplete()

            withClue("Debe registrarse cuándo se convirtió") {
                sql shouldContain "orders_converted_coherent"
            }
            withClue("Y debe haber estado de exportación con reintentos") {
                sql shouldContain "export_attempts"
                sql shouldContain "export_last_error"
            }
        }

        it("transit between warehouses has its own table and requires different warehouses") {
            // La mercancía que viaja SIGUE contando en el inventario. Si se
            // modelara como dos movimientos instantáneos, el total bajaría
            // durante el trayecto y los reportes mentirían.
            val sql = contentComplete()

            withClue("Debe existir la tabla de tránsito") {
                sql shouldContain "CREATE TABLE stock_in_transit"
            }
            withClue("Un traslado no puede ser a la misma bodega") {
                sql shouldContain "transit_different_warehouses"
            }
        }

        it("the RECEIVING location exists as a real location, not as a pseudo-location") {
            // El legacy devolvía 'ADMIN' hardcodeada con un id ficticio que podía
            // colisionar con la ubicación real 1 (hallazgo R-69).
            val sql = contentComplete()

            withClue("Debe existir el tipo de ubicación") {
                sql shouldContain "CREATE TYPE location_kind"
            }
            withClue("Y sembrarse una ubicación de recepción por bodega") {
                sql shouldContain "'RECEIVING'"
            }
        }

        it("the warehouses of the AREA scope live in the PERSON, not in the role") {
            // Es lo que permite que un solo rol WAREHOUSE_MANAGER sirva para cualquier
            // bodega, en lugar de crear un rol por cada una.
            val sql = contentComplete()

            withClue("Debe existir la tabla de bodegas por usuario") {
                sql shouldContain "CREATE TABLE user_warehouse_scopes"
            }
        }

        it("a person can hold several roles, with one primary") {
            // El legacy tenía `User.roleId` único, lo que obligaba a crear roles
            // híbridos cuando alguien cubría dos funciones.
            val sql = contentComplete()

            withClue("La relación usuario-rol debe ser N:M") {
                sql shouldContain "CREATE TABLE user_roles"
            }
            withClue("Y debe haber como máximo un rol principal por persona") {
                sql shouldContain "user_roles_one_primary_idx"
            }
        }

        it("the role key has a validated and stable format") {
            val sql = contentComplete()

            withClue("La clave debe tener un CHECK de formato") {
                sql shouldContain "roles_key_format"
            }
            withClue("Un rol de sistema debe tener descripción") {
                sql shouldContain "roles_description_present"
            }
        }

        it("permission keys follow the convention resource.action in lowercase") {
            val sql = contentComplete()

            withClue(
                "El CHECK del formato es lo que impide que se cuele un permiso con " +
                    "la convención invertida o en mayúsculas"
            ) {
                sql shouldContain "permissions_key_format"
            }
        }
    }

    describe("guardias contra operaciones destructivas") {

        it("no migration DROPs domain tables or TRUNCATEs") {
            // Un `DROP TABLE` en una migración borra datos de producción. La
            // única excepción legítima es la limpieza de una tabla temporal.
            val files = filesSql()

            val destructive = files.filter { file ->
                val content = file.readText()
                    .lines()
                    // Se ignoran los comentarios: documentar una operación
                    // destructiva no es ejecutarla.
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .uppercase()

                val deletesTable = content.contains("DROP TABLE") &&
                    !content.contains("DROP TABLE IF EXISTS")

                // CUIDADO con TRUNCATE: `REVOKE ... TRUNCATE` QUITA el permiso de
                // truncar, que es una medida de seguridad, no una operación
                // destructiva. Se busca el truncado real, que empieza la
                // sentencia, y no la mención del privilegio.
                val truncatesForReal = Regex("(^|;|\\n)\\s*TRUNCATE\\s", RegexOption.MULTILINE)
                    .containsMatchIn(content)

                deletesTable || truncatesForReal
            }

            withClue(
                "Estas migraciones contienen operaciones destructivas: " +
                    "${destructive.map { it.name }}. Una migración no debe borrar datos " +
                    "sin una revisión explícita."
            ) {
                destructive shouldBe emptyList()
            }
        }

        it("no migration deletes audit rows") {
            val sql = contentComplete().uppercase()

            withClue("Una migración no puede vaciar la auditoría: sería alterar la historia") {
                (sql.contains("DELETE FROM AUDIT_LOG")) shouldBe false
            }
        }
    }

    describe("integrated documentation") {

        it("the critical tables and columns are commented in the database") {
            val sql = contentComplete()

            // Los comentarios viven en la base y los ve quien la inspecciona.
            // Son la diferencia entre un esquema que se entiende y uno que hay
            // que arqueologizar.
            withClue("El ledger debe estar documentado") {
                sql shouldContain "COMMENT ON TABLE audit_log"
            }
            withClue("La traza de decisión debe explicar para qué sirve") {
                sql shouldContain "COMMENT ON COLUMN audit_log.access_trace"
            }
            withClue("El versionado debe explicar que permite reproducir decisiones") {
                sql shouldContain "COMMENT ON COLUMN audit_log.matrix_version"
            }
            withClue("Las dos horas del móvil offline deben estar explicadas") {
                sql shouldContain "COMMENT ON COLUMN audit_log.offline_created_at"
            }
        }
    }
})
