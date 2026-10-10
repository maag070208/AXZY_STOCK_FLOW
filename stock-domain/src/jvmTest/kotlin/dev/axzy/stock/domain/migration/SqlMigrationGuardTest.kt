package dev.axzy.stock.domain.migration

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SQL MIGRATION GUARDS
 *
 * This test runs on the JVM and reads the `.sql` files in the repository. It does not
 * connect to PostgreSQL: that is done by the CI workflow, which also applies the
 * migrations from scratch against a real database.
 *
 * **Why a test that reads SQL instead of testing it against a database?**
 * Because there are errors that are detected without a database and that it is important
 * to catch **in every commit**, not only in CI: that the migrations are
 * versioned sequentially, that the ledger's immutability guarantees remain
 * declared, and that nobody has slipped a `DROP` or a `DELETE` into a migration.
 *
 * The split is deliberate: here the **shape** is verified (cheap, in every
 * commit), in CI the **execution** is verified (expensive, against a real Postgres).
 *
 * The lesson from the legacy: it had 36 migrations in a folder that Prisma did not read
 * plus 3 in the correct folder, and one of them recreated the whole schema. Its
 * history **did not rebuild the database**, so a clean deployment was
 * impossible. These guards are the fix.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class SqlMigrationGuardTest : DescribeSpec({

    /**
     * It locates the migrations directory by walking up from the working
     * directory, so that the test works both from the monorepo root and from the
     * module directory.
     */
    fun migrationsDir(): File {
        val candidates = listOf(
            File("db/migrations"),
            File("../db/migrations"),
            File("../../db/migrations"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Migrations directory not found. Searched in: " +
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
                    "Migration '${file.name}' does not follow the convention " +
                        "V<n>__<name>.sql that Flyway requires. A badly formed name " +
                        "makes the migration NOT apply and fail silently."
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
            // Each of these enforces a business rule in the DATABASE,
            // not only in the code. Deleting them would turn an invariant into a
            // convention that can be forgotten.
            val sql = contentComplete()

            val checksExpected = mapOf(
                "audit_critical_has_permission" to
                    "A critical event without a permission cannot answer 'with which permission it was done'",
                "audit_critical_has_trace" to
                    "A critical event must store the trace, not only the result",
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
            // This is the detail discovered while verifying against PostgreSQL:
            // a trigger declared on a partitioned table does NOT apply to its
            // partitions. Putting it only on the parent gave a false sense of
            // protection, and an UPDATE on a partition PASSED.
            val sql = contentComplete()

            withClue("The function that enforces immutability per partition must exist") {
                sql shouldContain "audit_log_attach_immutability"
            }
            withClue("The partition function must call it when creating each partition") {
                sql shouldContain "PERFORM audit_log_attach_immutability(nombre)"
            }
            withClue("The default partition must also receive it") {
                sql shouldContain "audit_log_attach_immutability('audit_log_default')"
            }
        }

        it("the audit log blocks UPDATE and DELETE with a message that explains why") {
            val sql = contentComplete()

            withClue("The function that blocks mutations must exist") {
                sql shouldContain "audit_log_block_mutations"
            }
            withClue("The message must explain the reason, not just fail") {
                sql shouldContain "append-only"
                sql shouldContain "La auditoría que se puede editar no es auditoría"
            }
        }

        it("the ledger uses a SHARED SEQUENCE, not IDENTITY per partition") {
            // The parent's GENERATED ALWAYS AS IDENTITY does not apply to the
            // partitions: every insert failed with 'null value in column id'.
            // A shared sequence also guarantees a GLOBAL order of the
            // ledger, not one per partition.
            val sql = contentComplete()

            withClue("Debe existir la secuencia compartida") {
                sql shouldContain "CREATE SEQUENCE audit_log_id_seq"
            }
            withClue("La tabla debe usarla como valor por defecto") {
                sql shouldContain "DEFAULT nextval('audit_log_id_seq')"
            }
            withClue(
                "No debe usarse IDENTITY: no se aplica a las particiones y las " +
                    "inserts would fail"
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
            // The ledger is the inventory book. In the legacy,
            // `removeUnitsFromKardex` did `kardex.update`: a historical
            // record was modified and the traceability was destroyed.
            val sql = contentComplete()

            withClue("The function that blocks mutations of the ledger must exist") {
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
            withClue("The phone's idempotency is protected by a unique index") {
                sql shouldContain "kardex_client_uuid_idx"
            }
        }

        it("`difference` is a GENERATED COLUMN, not a field that can get out of sync") {
            // The legacy declared `difference` in the model, DTO, mapper and
            // response, and always wrote 0 (finding R-58).
            val sql = contentComplete()

            withClue("La columna debe ser GENERATED ALWAYS AS ... STORED") {
                sql shouldContain "GENERATED ALWAYS AS (physical_quantity - quantity) STORED"
            }
        }

        it("stock per location and the total cannot be negative (invariant I2)") {
            val sql = contentComplete()

            withClue("The per-location projection has a CHECK") {
                sql shouldContain "stock_non_negative"
            }
            withClue("And so does the total per product") {
                sql shouldContain "product_stock_non_negative"
            }
        }

        it("an invoice line does not reach APPROVED without an approver or without a reason") {
            // It is rule B1 and the segregation of duties (R-74) enforced in
            // the database: without this, internal control depends on the
            // code remembering to validate it.
            val sql = contentComplete()

            withClue("Debe exigirse aprobador") {
                sql shouldContain "invoice_lines_approved_has_approver"
            }
            withClue("Y motivo del ajuste") {
                sql shouldContain "invoice_lines_approved_has_reason"
            }
        }

        it("deleting documents leaves a trace: nothing is deleted without a reason") {
            // The legacy declared `deleted` in the schema and did a physical DELETE,
            // never writing the flag (finding B-1 / R-57).
            val sql = contentComplete()

            withClue("The invoice requires a reason and a date if it is deleted") {
                sql shouldContain "invoices_deleted_coherent"
            }
            withClue("The order too") {
                sql shouldContain "orders_deleted_coherent"
            }
        }

        it("a label is not marked printed until the printer confirms") {
            // The legacy set `printed = true` without waiting for the response of the
            // POST: if the Zebra was off, the label was lost for
            // siempre (hallazgo R-83).
            val sql = contentComplete()

            withClue("The print state machine must exist") {
                sql shouldContain "CREATE TYPE print_status"
            }
            withClue("A PRINTED label must have the confirmation time") {
                sql shouldContain "print_jobs_printed_has_timestamp"
            }
            withClue("El payload debe tener esquema validable") {
                sql shouldContain "field_schema"
            }
        }

        it("the conversion to the ERP has a status and not only a boolean") {
            // The conversion flow NEVER existed in the legacy: `converted` and
            // `sent` existed but were neither written nor read (finding R-82).
            val sql = contentComplete()

            withClue("When it was converted must be recorded") {
                sql shouldContain "orders_converted_coherent"
            }
            withClue("And there must be an export state with retries") {
                sql shouldContain "export_attempts"
                sql shouldContain "export_last_error"
            }
        }

        it("transit between warehouses has its own table and requires different warehouses") {
            // Goods in transit STILL COUNT in the inventory. If they were
            // modeled as two instantaneous movements, the total would drop
            // during transit and the reports would lie.
            val sql = contentComplete()

            withClue("The transit table must exist") {
                sql shouldContain "CREATE TABLE stock_in_transit"
            }
            withClue("Un traslado no puede ser a la misma bodega") {
                sql shouldContain "transit_different_warehouses"
            }
        }

        it("the RECEIVING location exists as a real location, not as a pseudo-location") {
            // The legacy returned a hardcoded 'ADMIN' with a fictitious id that could
            // collide with the real location 1 (finding R-69).
            val sql = contentComplete()

            withClue("The location type must exist") {
                sql shouldContain "CREATE TYPE location_kind"
            }
            withClue("And a receiving location must be seeded per warehouse") {
                sql shouldContain "'RECEIVING'"
            }
        }

        it("the warehouses of the AREA scope live in the PERSON, not in the role") {
            // It is what allows a single WAREHOUSE_MANAGER role to serve any
            // warehouse, instead of creating one role per warehouse.
            val sql = contentComplete()

            withClue("Debe existir la tabla de bodegas por usuario") {
                sql shouldContain "CREATE TABLE user_warehouse_scopes"
            }
        }

        it("a person can hold several roles, with one primary") {
            // The legacy had a single `User.roleId`, which forced creating
            // hybrid roles when someone covered two functions.
            val sql = contentComplete()

            withClue("The user-role relation must be N:M") {
                sql shouldContain "CREATE TABLE user_roles"
            }
            withClue("And there must be at most one primary role per person") {
                sql shouldContain "user_roles_one_primary_idx"
            }
        }

        it("the role key has a validated and stable format") {
            val sql = contentComplete()

            withClue("La clave debe tener un CHECK de formato") {
                sql shouldContain "roles_key_format"
            }
            withClue("A system role must have a description") {
                sql shouldContain "roles_description_present"
            }
        }

        it("permission keys follow the convention resource.action in lowercase") {
            val sql = contentComplete()

            withClue(
                "El CHECK del formato es lo que impide que se cuele un permiso con " +
                    "the inverted or uppercase convention"
            ) {
                sql shouldContain "permissions_key_format"
            }
        }
    }

    describe("guardias contra operaciones destructivas") {

        it("no migration DROPs domain tables or TRUNCATEs") {
            // A `DROP TABLE` in a migration deletes production data. The
            // only legitimate exception is cleaning up a temporary table.
            val files = filesSql()

            val destructive = files.filter { file ->
                val content = file.readText()
                    .lines()
                    // Comments are ignored: documenting a destructive operation
                    // is not executing it.
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .uppercase()

                val deletesTable = content.contains("DROP TABLE") &&
                    !content.contains("DROP TABLE IF EXISTS")

                // CAREFUL with TRUNCATE: `REVOKE ... TRUNCATE` REMOVES the privilege to
                // truncate, which is a security measure, not a destructive
                // operation. The real truncation is what is searched for, the one that starts the
                // statement, and not the mention of the privilege.
                val truncatesForReal = Regex("(^|;|\\n)\\s*TRUNCATE\\s", RegexOption.MULTILINE)
                    .containsMatchIn(content)

                deletesTable || truncatesForReal
            }

            withClue(
                "Estas migraciones contienen operaciones destructivas: " +
                    "${destructive.map { it.name }}. A migration must not delete data " +
                    "without an explicit review."
            ) {
                destructive shouldBe emptyList()
            }
        }

        it("no migration deletes audit rows") {
            val sql = contentComplete().uppercase()

            withClue("A migration cannot empty the audit log: that would alter history") {
                (sql.contains("DELETE FROM AUDIT_LOG")) shouldBe false
            }
        }
    }

    describe("integrated documentation") {

        it("the critical tables and columns are commented in the database") {
            val sql = contentComplete()

            // Comments live in the database and are seen by whoever inspects it.
            // They are the difference between a schema that is understood and one that has
            // to be archaeologized.
            withClue("El ledger debe estar documentado") {
                sql shouldContain "COMMENT ON TABLE audit_log"
            }
            withClue("The decision trace must explain what it is for") {
                sql shouldContain "COMMENT ON COLUMN audit_log.access_trace"
            }
            withClue("El versionado debe explicar que permite reproducir decisiones") {
                sql shouldContain "COMMENT ON COLUMN audit_log.matrix_version"
            }
            withClue("The two offline phone times must be explained") {
                sql shouldContain "COMMENT ON COLUMN audit_log.offline_created_at"
            }
        }
    }
})
