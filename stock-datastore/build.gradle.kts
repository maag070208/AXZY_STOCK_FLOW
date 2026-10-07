// ============================================================================
// stock-datastore — Preferencias multiplataforma (KMP)
//
// Responsabilidad: configuración del servidor (AppConfig con esquema, host y
// puerto, en lugar de la IP pelada del legacy), preferencias de usuario,
// idioma y almacenamiento seguro del token.
//
// Regla que sostiene este módulo: **una preferencia y un secreto no son lo
// mismo**. Las preferencias van a un almacén de clave-valor legible (DataStore
// en Android, NSUserDefaults en iOS); los tokens de sesión van a un almacén
// protegido por el sistema (Keystore en Android, Keychain en iOS). Mezclarlos
// fue el defecto de partida: un refresh token de 30 días en un archivo de
// preferencias legible es una sesión regalada.
// ============================================================================

plugins {
    // Ver el comentario largo en stock-network/build.gradle.kts.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
}

// ── TARGETS ────────────────────────────────────────────────────────────────
// JVM SIEMPRE: es donde corren los tests del contrato común.
//
// iOS es OPT-IN, con la misma propiedad que stock-domain:
//   ./gradlew -PenableIosTargets=true build
val enableIosTargets = providers.gradleProperty("enableIosTargets")
    .map(String::toBoolean)
    .getOrElse(false)

/** Compatibilidad con el nombre anterior de la propiedad. */
val enableNativeTargets = providers.gradleProperty("enableNativeTargets")
    .map(String::toBoolean)
    .getOrElse(false)

kotlin {
    jvm()

    if (enableIosTargets || enableNativeTargets) {
        iosArm64()
        iosSimulatorArm64()
    }

    android {
        namespace = "dev.axzy.stock.datastore"
        compileSdk {
            version = release(libs.versions.androidCompileSdk.get().toInt())
        }
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    // ¿Por qué este módulo NO depende de stock-domain ni de stock-network?
    // Porque es infraestructura de almacenamiento: si dependiera de ellos, para
    // guardar la URL del servidor habría que arrastrar el cliente HTTP entero, y
    // el móvil no podría usar las preferencias antes de tener red. El puerto
    // que el cliente HTTP necesita (`TokenStorePort`, en stock-network) se
    // implementa en la app que los une, con un adaptador de tres líneas.
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            // El reloj con el que se decide si un access token ya caducó. Se usa
            // el mismo que en el resto del proyecto para que no haya dos ideas
            // distintas de «ahora».
            implementation(libs.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies {
            // DataStore en Android: la escritura es una corrutina que termina o
            // falla, en lugar del `apply()` asíncrono de SharedPreferences que
            // nunca avisa de que no se guardó.
            implementation(libs.androidx.datastore.preferences)
        }
        // iOS no necesita dependencias: NSUserDefaults (preferencias) y
        // Security.framework (Keychain) vienen con la plataforma.
    }
}

// El reporte XML es la única prueba de que los tests se ejecutaron: Gradle
// informa «BUILD SUCCESSFUL» igual con cero tests.
tasks.withType<Test>().configureEach {
    reports {
        junitXml.required.set(true)
        html.required.set(true)
    }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
