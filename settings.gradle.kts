// STOCK: dominio compartido (KMP) y API (Kotlin + Spring Boot).
// La app móvil y la web son builds propios; ver docs/plan/PLAN-IMPLEMENTACION.md.

rootProject.name = "stock"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Descarga el JDK de cada toolchain (17 para el dominio, 25 para la API) si no está instalado.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Reglas de negocio puras, compartidas con la app. No depende de ningún framework.
include(":stock-domain")

// La API en capas (docs/arquitectura/api-spring.md). La dependencia solo apunta hacia el dominio.
include(":api:application")
include(":api:adapters-persistence")
include(":api:adapters-security")
include(":api:adapters-web")
include(":api:app")
