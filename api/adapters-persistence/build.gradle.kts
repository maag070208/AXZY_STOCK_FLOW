// ── adapters-persistence: los puertos de salida, sobre PostgreSQL ───────────
// Implementa los puertos de `application` con JdbcClient y SQL escrito a mano. Las escrituras de
// inventario llaman a las funciones del motor (docs/adr/0015): aquí no hay ORM.
// NO conoce a `adapters-web`.
plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    implementation(project(":api:application"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    // La base de pruebas es PostgreSQL real con las migraciones reales. La comparten los tests de
    // este módulo y los de flujo completo de `app`.
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.springframework.boot:spring-boot-starter-jdbc")
    testFixturesApi(project(":api:application"))
    testFixturesImplementation("tools.jackson.module:jackson-module-kotlin")
    testFixturesImplementation("org.flywaydb:flyway-core")
    testFixturesImplementation("org.flywaydb:flyway-database-postgresql")
    testFixturesRuntimeOnly("org.postgresql:postgresql")

    testImplementation(testFixtures(project))
}

// Los tests aplican las migraciones reales: necesitan saber dónde están.
tasks.withType<Test>().configureEach {
    systemProperty("stock.db.dir", rootProject.layout.projectDirectory.dir("db").asFile.absolutePath)
}
