// ============================================================================
// androidApp — punto de entrada Android
//
// QUÉ HACE Y QUÉ NO
//   · Hace de APLICACIÓN: manifest, tema, icono y una Activity que monta `App()`.
//   · NO tiene UI propia. Toda la interfaz vive en `mobile-app`, compartida con iOS.
//     Una pantalla escrita aquí sería una pantalla que iOS no tiene, y el plan
//     exige el mismo flujo A y C operable en las dos plataformas (Fase 6).
//
// ES UN MÓDULO APARTE Y NO UN TARGET DE `mobile-app` POR UNA RAZÓN CONCRETA:
// `mobile-app` usa `com.android.kotlin.multiplatform.library` (una BIBLIOTECA KMP,
// que no genera APK); una aplicación Android necesita `com.android.application`, y
// ese plugin no se puede aplicar al mismo módulo. Es la misma separación que usa el
// ejemplo real de PuertoNuevoApp (`shared/` + `androidApp/`).
// ============================================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Versiones fijadas en `pluginManagement` (settings.gradle.kts). Ver el
    // comentario largo de ese archivo.
    id("com.android.application")
    id("org.jetbrains.compose")
    // NO se aplica `org.jetbrains.kotlin.android`: AGP 9 ya trae soporte de Kotlin
    // integrado, y la guía de migración indica explícitamente eliminar ese plugin.
    // Aplicarlo además es lo que provoca «Could not generate a decorated class for
    // type KotlinAndroidTarget ... com/android/build/gradle/api/BaseVariant», una
    // clase que AGP 9 eliminó.
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "dev.axzy.stock.mobile.android"
    // Aquí SÍ vale la forma escalar: es el DSL de `com.android.application`, no el
    // de AGP 9 para KMP. En `mobile-app` (plugin KMP) la forma escalar da
    // «compileSdk version is not set» y hace falta el bloque `compileSdk { }`.
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.axzy.stock.mobile"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    signingConfigs {
        // Se declara un almacén de claves de depuración propio porque AGP intenta
        // crear el suyo en `~/.android` y en este entorno ese directorio no es
        // escribible (el sandbox lo bloquea), así que `validateSigningDebug` fallaba
        // con «Unable to create debug keystore ... because it is not writable».
        //
        // NO ES UN SECRETO: es la clave de depuración estándar (android/android), la
        // misma que AGP genera en cualquier máquina, y solo sirve para instalar en un
        // dispositivo de pruebas. La clave de release NO va aquí.
        // Se RECONFIGURA el `debug` que AGP ya crea (no se puede crear otro con el
        // mismo nombre), cambiándole solo la ruta del almacén de claves.
        getByName("debug") {
            storeFile = file("/Users/axzy/DEV/STOCK/.gradle-home/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // El monorepo entra como build INCLUIDO (ver settings.gradle.kts), así que la
    // dependencia se declara por coordenada: Gradle la sustituye por el proyecto
    // `:mobile-app` de ese build, sin publicar nada en un repositorio.
    implementation("dev.axzy.stock:mobile-app")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    debugImplementation(libs.compose.ui.tooling)
}
