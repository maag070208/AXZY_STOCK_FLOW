package dev.axzy.stock.app

import dev.axzy.stock.app.config.SchemaGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class SchemaVersionTest {

    @Test
    fun `the schema version the API requires is the latest migration in the repository`() {
        val migrations = File(System.getProperty("stock.db.dir"), "migrations")
        val latest = migrations.listFiles { file -> file.name.matches(Regex("V\\d+__.*\\.sql")) }!!
            .maxOf { it.name.substringAfter('V').substringBefore("__").toInt() }

        // Si esto falla, alguien añadió una migración y no subió REQUIRED_SCHEMA_VERSION: la API
        // arrancaría contra una base sin ella y fallaría más tarde, en la ruta que la necesita.
        assertThat(SchemaGuard.REQUIRED_SCHEMA_VERSION).isEqualTo(latest)
    }
}
