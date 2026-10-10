// ── app: el arranque y el cableado ──────────────────────────────────────────
// El ÚNICO módulo que ve todas las capas. Crea los casos de uso con sus puertos, configura la
// seguridad HTTP y arranca Spring Boot. Si un objeto se crea, se crea aquí.
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":api:application"))
    implementation(project(":api:adapters-persistence"))
    implementation(project(":api:adapters-security"))
    implementation(project(":api:adapters-web"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":api:adapters-persistence")))
    testImplementation(libs.archunit)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("stock-api.jar")
}

// Los tests de flujo aplican las migraciones reales: necesitan saber dónde están.
tasks.withType<Test>().configureEach {
    systemProperty("stock.db.dir", rootProject.layout.projectDirectory.dir("db").asFile.absolutePath)
}
