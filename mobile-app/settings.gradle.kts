// ============================================================================
// settings.gradle.kts de `mobile-app` — BUILD KMP AUTOCONTENIDO
//
// POR QUÉ ESTE ARCHIVO EXISTE
//
// Antes `mobile-app` era un submódulo del build del monorepo, y el APK Android
// tenía que vivir en un build de Gradle APARTE (`androidApp/settings.gradle.kts`)
// porque se creía que `com.android.application` y el plugin de Kotlin
// Multiplatform no podían convivir. Con AGP 9 sí pueden: el plugin de Android
// para KMP (`com.android.kotlin.multiplatform.library`) declara el target de
// Android DENTRO del bloque `kotlin {}` y no arrastra `KotlinAndroidTarget`, que
// es la clase que AGP 9 eliminó (`com.android.build.gradle.api.BaseVariant`).
//
// Con este `settings.gradle.kts`, `mobile-app` es un build de Kotlin
// Multiplatform normal —como el proyecto de referencia `PuertoNuevoApp`—, con
// sus dos módulos y SIN builds anidados:
//
//   mobile-app/
//   ├── build.gradle.kts     ← declara los plugins con `apply false`
//   ├── settings.gradle.kts  ← este archivo
//   ├── shared/              ← la biblioteca KMP (UI compartida Android + iOS)
//   └── androidApp/          ← el APK (com.android.application)
//
// EL PRECIO (y por qué se paga con gusto)
//
// El dominio (`stock-domain`) NO puede ser un módulo de ESTE build: vive en el
// build del monorepo, que a su vez consumen el backend y los tests de negocio.
// Duplicarlo aquí sería tener dos definiciones de las reglas de negocio, que es
// exactamente lo que el módulo compartido existe para evitar
// (docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.2-bis). Se consume como BUILD INCLUIDO
// (`includeBuild("..")`), que es la forma canónica de Gradle de componer dos
// builds independientes sin publicar nada en un repositorio Maven.
//
// LO QUE ESTO ARREGLA, QUE ERA EL PROBLEMA DE VERDAD
//
// Con el esquema anterior, abrir `mobile-app` en solitario en IntelliJ fallaba
// con «Task 'prepareKotlinBuildScriptModel' not found in project ':mobile-app'»:
// la raíz del proyecto abierto no era la raíz de ningún build de Gradle.
// Ahora `mobile-app` ES la raíz de su build, así que el IDE sincroniza como con
// cualquier proyecto KMP.
// ============================================================================

rootProject.name = "mobile-app"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }

    // ── Versiones de los plugins de este build, resueltas AQUÍ ──────────────
    //
    // Estas cinco líneas son las únicas que repiten los números de
    // `../gradle/libs.versions.toml`. El motivo es corto: las versiones que se
    // declaran en el `pluginManagement` NO se pueden tomar del catálogo (el
    // catálogo no existe todavía cuando se evalúa este bloque), así que hay que
    // escribirlas. Al subir AGP, Compose o Kotlin hay que tocar LOS DOS sitios.
    //
    // Sobre POR QUÉ el build del MONOREPO no puede usar `apply false` en su raíz
    // —lo que la guía oficial de AGP 9 recomienda— y aquí sí: está reproducido y
    // explicado en el README de `mobile-app/` §9. En una línea: declarar un plugin
    // de AGP 9 con `apply false` mete el artefacto ENTERO de AGP en el classpath
    // de script de todos los subproyectos, y entonces el `android {}` de un módulo
    // que no aplica `android-kmp-library` resuelve al `android()` obsoleto de KMP
    // («Unresolved reference 'namespace'»).
    //
    // Aquí no pasa porque los DOS módulos de este build aplican su plugin de AGP
    // (o ninguno, como `androidApp`, que no usa el bloque `android {}` de KMP).
    plugins {
        id("com.android.application") version "9.1.1"
        id("com.android.kotlin.multiplatform.library") version "9.1.1"
        id("org.jetbrains.compose") version "1.11.1"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// ── El dominio, como build INCLUIDO ────────────────────────────────────────
//
// `includeBuild("..")` mete el build del monorepo en este: Gradle CONFIGURA
// solo los proyectos de los que se declara dependencia (no los nueve módulos) y
// sustituye la coordenada por el proyecto real sin publicar nada.
//
// La sustitución va por COORDENADA (`dev.axzy.stock:stock-domain`) y no por
// `project(":stock-domain")`. No es estilo: la sustitución de dependencias de la
// composición de builds solo cubre dependencias de MÓDULO. Un `project(":...")`
// se resuelve dentro del build que lo escribe y no cruza la frontera, así que
// `shared` fallaba con «Project with path ':stock-domain' could not be found».
//
// La contrapartida: el módulo tiene que tener grupo. El build del monorepo lo
// fija de una vez para todos sus módulos en `settings.gradle.kts`, y ese cambio
// es la ÚNICA línea que se tocó fuera de `mobile-app/` (motivo en el comentario
// de ese archivo).
includeBuild("..") {
    dependencySubstitution {
        substitute(module("dev.axzy.stock:stock-domain")).using(project(":stock-domain"))
    }
}

include(":shared")
include(":androidApp")
