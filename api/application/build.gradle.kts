// ── application: casos de uso y puertos ─────────────────────────────────────
// Depende SOLO del dominio. Ni Spring, ni JDBC, ni Jackson: un caso de uso se construye y se
// prueba con `new`, pasándole fakes de sus puertos.
plugins {
    `java-library`
}

dependencies {
    // `api`: quien use un caso de uso ve también los tipos del dominio que este expone.
    api(project(":stock-domain"))
}
