// ============================================================================
// build.gradle.kts de `mobile-app` — raíz del build KMP
//
// POR QUÉ LOS PLUGINS SE DECLARAN AQUÍ CON `apply false`
//
// Es lo que pide la guía oficial de migración a AGP 9 con Kotlin Multiplatform,
// y aquí SÍ funciona (en el build del monorepo rompía: ver el README §9). Estando
// `apply false`:
//
//   · la VERSIÓN se resuelve una sola vez, en este build, y no puede haber dos
//     versiones del mismo plugin en dos subproyectos;
//   · las CLASES del plugin NO entran en el classpath del script de la raíz, así
//     que no hay dos definiciones de `android {}` compitiendo (la del plugin de
//     AGP 9 y el `android()` obsoleto que todavía trae la API de KMP).
//
// Lo mismo que hace el `build.gradle.kts` de la raíz del proyecto de referencia
// `PuertoNuevoApp`, y por el mismo motivo.
//
// QUIÉN APLICA QUÉ
//   shared/     → kotlin-multiplatform + android-kmp-library + compose + serialization
//   androidApp/ → com.android.application + compose-compiler
//
// Se declaran los SEIS aunque `androidApp` no use serialization ni KMP: la lista
// es la de este build, no la de cada módulo. Dejar uno fuera haría que su versión
// se resolviera por el `pluginManagement`, con dos números que se pueden separar.
// ============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
