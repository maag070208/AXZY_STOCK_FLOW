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

    /*
     * XLSX: la exportación del kardex (fase 5) y, más adelante, la lectura del Excel
     * del catálogo (M10). La versión la gestiona el BOM de Spring Boot; el motor de
     * hoja de cálculo es una dependencia grande y entra por una razón concreta, no
     * por comodidad: escribir un XLSX a mano son tres XML y un ZIP, y leerlo es
     * bastante más, así que la biblioteca se paga sola en cuanto hay que hacer las dos.
     */
    implementation(libs.poi.ooxml)

    // `MockMultipartFile`: probar el lector del archivo sin arrancar un servidor.
    testImplementation("org.springframework:spring-test")
}
