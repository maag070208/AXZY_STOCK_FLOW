// ============================================================================
// settings.gradle.kts — Monorepo del Almacén Integral
//
// ARQUITECTURA DE MÓDULOS (grafo unidireccional, sin ciclos):
//
//   backend ──→ stock-domain ──→ (solo stdlib Kotlin + kotlinx)
//   mobile  ──→ stock-domain
//           ──→ stock-network ──→ stock-domain
//           ──→ stock-database ─→ stock-domain
//           ──→ stock-datastore
//
// stock-domain vive en la RAÍZ a propósito: es un ciudadano de primera clase,
// consumido por el backend Y por el móvil. No es un detalle de implementación
// de ninguno de los dos. Ver PLAN_ALMACEN_INTEGRAL.md §3.1 y §3.2-bis.
// ============================================================================

rootProject.name = "stock"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// ── Módulos Kotlin Multiplatform compartidos ────────────────────────────────
// stock-domain: el corazón del sistema. Kotlin PURO, sin Ktor, sin Exposed,
//   sin SQLDelight, sin Compose, sin androidx. Una guardia de arquitectura en
//   el build FALLA si esto se rompe (ver stock-domain/build.gradle.kts).
include(":stock-domain")
include(":stock-network")
include(":stock-database")
include(":stock-datastore")

// ── Backend Ktor ───────────────────────────────────────────────────────────
include(":backend")

// El módulo de móvil (Compose Multiplatform) se añadirá en la Fase 6.
// Se incluye comentado para que el árbol de Gradle no falle al no existir aún:
// include(":mobile:composeApp")
