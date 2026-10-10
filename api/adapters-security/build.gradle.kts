// ── adapters-security: criptografía detrás de puertos ───────────────────────
// Firma y verificación de tokens, hash de contraseñas (Argon2id) y de refresh tokens. Ningún
// algoritmo está escrito a mano: son las implementaciones de Spring Security y Nimbus.
plugins {
    `java-library`
}

dependencies {
    implementation(project(":api:application"))
    implementation("org.springframework.security:spring-security-crypto")
    // `api`: el decodificador de JWT que crea este módulo lo usa `app` para configurar la seguridad HTTP.
    api("org.springframework.security:spring-security-oauth2-jose")
    implementation(libs.bouncycastle)
}
