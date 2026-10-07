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
    fun directorioDeMigraciones(): File {
        val candidatos = listOf(
            File("backend/src/main/resources/db/migration"),
            File("../backend/src/main/resources/db/migration"),
            File("../../backend/src/main/resources/db/migration"),
        )
        return candidatos.firstOrNull { it.isDirectory }
            ?: error(
                "No se encontró el directorio de migraciones. Buscado en: " +
                    candidatos.joinToString { it.absolutePath }
            )
    }

    fun archivosSql(): List<File> = directorioDeMigraciones()
        .listFiles { f -> f.extension == "sql" }
        ?.sortedBy { it.name }
        ?: emptyList()

    fun contenidoCompleto(): String = archivosSql().joinToString("\n") { it.readText() }

    describe("versionado de las migraciones") {

        it("existen migraciones y siguen la convención V<n>__<nombre>.sql") {
            val archivos = archivosSql()

            withClue("Debe haber al menos una migración") {
                (archivos.isNotEmpty()) shouldBe true
            }

            val patron = Regex("^V\\d+__[a-z0-9_]+\\.sql$")
            archivos.forEach { archivo ->
                withClue(
                    "La migración '${archivo.name}' no sigue la convención " +
                        "V<n>__<nombre>.sql que exige Flyway. Un nombre mal formado " +
                        "hace que la migración NO se aplique y falle en silencio."
                ) {
                    patron.matches(archivo.name) shouldBe true
                }
            }
        }

        it("las versiones son correlativas desde 1, sin huecos ni duplicados") {
            val versiones = archivosSql()
                .map { it.name.substringAfter('V').substringBefore("__").toInt() }
                .sorted()

            withClue("Versiones encontradas: $versiones") {
                versiones shouldBe (1..versiones.size).toList()
            }
        }
    }

    describe("guardias de seguridad del esquema") {

        it("los CHECK del dominio de auditoría siguen declarados") {
            // Cada uno de estos impone una regla de negocio en la BASE DE DATOS,
            // no solo en el código. Borrarlos convertiría una invariante en una
            // convención que se puede olvidar.
            val sql = contenidoCompleto()

            val checksEsperados = mapOf(
                "audit_critical_has_permission" to
                    "Un evento crítico sin permiso no puede responder 'con qué permiso se hizo'",
                "audit_critical_has_trace" to
                    "Un evento crítico debe guardar la traza, no solo el resultado",
                "audit_adjustment_has_reason" to
                    "Todo ajuste de inventario exige motivo (invariante I6)",
                "audit_human_has_actor" to
                    "Si lo hizo una persona, debe estar identificada",
            )

            checksEsperados.forEach { (nombre, razon) ->
                withClue("Falta el CHECK '$nombre': $razon") {
                    sql shouldContain nombre
                }
            }
        }

        it("la inmutabilidad de la auditoría se aplica POR PARTICIÓN") {
            // Éste es el detalle que se descubrió verificando contra PostgreSQL:
            // un trigger declarado en una tabla particionada NO se aplica a sus
            // particiones. Ponerlo solo en el padre daba una falsa sensación de
            // protección, y un UPDATE sobre una partición PASABA.
            val sql = contenidoCompleto()

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

        it("la auditoría bloquea UPDATE y DELETE con un mensaje que explica por qué") {
            val sql = contenidoCompleto()

            withClue("Debe existir la función que bloquea las mutaciones") {
                sql shouldContain "audit_log_block_mutations"
            }
            withClue("El mensaje debe explicar la razón, no solo fallar") {
                sql shouldContain "append-only"
                sql shouldContain "La auditoría que se puede editar no es auditoría"
            }
        }

        it("el ledger usa una SECUENCIA COMPARTIDA, no IDENTITY por partición") {
            // El GENERATED ALWAYS AS IDENTITY del padre no se aplica a las
            // particiones: toda inserción fallaba con 'null value in column id'.
            // Una secuencia compartida garantiza además un orden GLOBAL del
            // ledger, no uno por partición.
            val sql = contenidoCompleto()

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

        it("la matriz de permisos liga rol, permiso y alcance, y no concede NONE") {
            val sql = contenidoCompleto()

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

        it("las bodegas del alcance AREA viven en la PERSONA, no en el rol") {
            // Es lo que permite que un solo rol JEFE_BODEGA sirva para cualquier
            // bodega, en lugar de crear un rol por cada una.
            val sql = contenidoCompleto()

            withClue("Debe existir la tabla de bodegas por usuario") {
                sql shouldContain "CREATE TABLE user_warehouse_scopes"
            }
        }

        it("una persona puede tener varios roles, con uno principal") {
            // El legacy tenía `User.roleId` único, lo que obligaba a crear roles
            // híbridos cuando alguien cubría dos funciones.
            val sql = contenidoCompleto()

            withClue("La relación usuario-rol debe ser N:M") {
                sql shouldContain "CREATE TABLE user_roles"
            }
            withClue("Y debe haber como máximo un rol principal por persona") {
                sql shouldContain "user_roles_one_primary_idx"
            }
        }

        it("la clave del rol tiene formato validado y es estable") {
            val sql = contenidoCompleto()

            withClue("La clave debe tener un CHECK de formato") {
                sql shouldContain "roles_key_format"
            }
            withClue("Un rol de sistema debe tener descripción") {
                sql shouldContain "roles_description_present"
            }
        }

        it("las claves de permiso siguen la convención recurso.accion en minúsculas") {
            val sql = contenidoCompleto()

            withClue(
                "El CHECK del formato es lo que impide que se cuele un permiso con " +
                    "la convención invertida o en mayúsculas"
            ) {
                sql shouldContain "permissions_key_format"
            }
        }
    }

    describe("guardias contra operaciones destructivas") {

        it("ninguna migración hace DROP de tablas del dominio ni TRUNCATE") {
            // Un `DROP TABLE` en una migración borra datos de producción. La
            // única excepción legítima es la limpieza de una tabla temporal.
            val archivos = archivosSql()

            val destructivas = archivos.filter { archivo ->
                val contenido = archivo.readText()
                    .lines()
                    // Se ignoran los comentarios: documentar una operación
                    // destructiva no es ejecutarla.
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .uppercase()

                val borraTabla = contenido.contains("DROP TABLE") &&
                    !contenido.contains("DROP TABLE IF EXISTS")

                // CUIDADO con TRUNCATE: `REVOKE ... TRUNCATE` QUITA el permiso de
                // truncar, que es una medida de seguridad, no una operación
                // destructiva. Se busca el truncado real, que empieza la
                // sentencia, y no la mención del privilegio.
                val truncaDeVerdad = Regex("(^|;|\\n)\\s*TRUNCATE\\s", RegexOption.MULTILINE)
                    .containsMatchIn(contenido)

                borraTabla || truncaDeVerdad
            }

            withClue(
                "Estas migraciones contienen operaciones destructivas: " +
                    "${destructivas.map { it.name }}. Una migración no debe borrar datos " +
                    "sin una revisión explícita."
            ) {
                destructivas shouldBe emptyList()
            }
        }

        it("ninguna migración borra filas de la auditoría") {
            val sql = contenidoCompleto().uppercase()

            withClue("Una migración no puede vaciar la auditoría: sería alterar la historia") {
                (sql.contains("DELETE FROM AUDIT_LOG")) shouldBe false
            }
        }
    }

    describe("documentación integrada") {

        it("las tablas y columnas críticas están comentadas en la base de datos") {
            val sql = contenidoCompleto()

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
