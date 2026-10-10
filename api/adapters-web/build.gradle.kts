// ── adapters-web: los puertos de entrada, por HTTP ──────────────────────────
// Controladores que traducen HTTP ↔ casos de uso, y el contrato de error (docs/api/errores.md).
// Un controlador NUNCA llama a un repositorio: NO conoce a `adapters-persistence`.
plugins {
    `java-library`
}

dependencies {
    implementation(project(":api:application"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Solo para leer la identidad ya verificada (`Authentication`); la cadena de filtros se configura en `app`.
    implementation("org.springframework.security:spring-security-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
}
