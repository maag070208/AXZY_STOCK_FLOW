# App — Vertical slice en KMP (`mobile-app/`)
> **Normativo.** Build, targets y organización de la app móvil.
> Última verificación: 2026-10-09.

La decisión está en [ADR 0003](../adr/0003-vertical-slice-en-la-app.md).

## 3.1 La arquitectura: build KMP autocontenido + vertical slice

**`mobile-app` es su propio build de Gradle**, separado del monorepo
(`mobile-app/settings.gradle.kts`). No es un submódulo: **un directorio no puede ser raíz de
dos builds de Gradle**, e `include(":mobile-app")` en la raíz haría que IntelliJ no pudiera
sincronizarlo.

```
mobile-app/
├── settings.gradle.kts      include(":shared") + include(":androidApp") + includeBuild("..")
├── build.gradle.kts         los plugins con `apply false`
├── shared/                  la biblioteca KMP
│   └── src/{commonMain,androidMain,iosMain,jvmMain,jvmTest}
│       └── dev/axzy/stock/mobile/
│           ├── App.kt       navegación y montaje
│           ├── core/        theme, ui, camera, data, nav — compartido
│           └── feature/     una carpeta por funcionalidad
├── androidApp/              el APK, submódulo normal
└── iosApp/                  fuentes Swift y Config.xcconfig
```

**La organización es vertical slice:** `core/` es lo compartido y cada funcionalidad vive en
su carpeta bajo `feature/` (`mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/`).
Cambiar una funcionalidad toca una sola carpeta.

**Consume el dominio por `includeBuild("..")`**, que es **la misma instancia y no una copia**:
`mobile-app/settings.gradle.kts` sustituye la coordenada `dev.axzy.stock:stock-domain`. Las
decisiones de negocio son idénticas en backend y móvil **por construcción**.

## 3.2 Targets: por qué iOS es opt-in

| Target | Cuándo |
|---|---|
| **JVM** | siempre — es lo que corre los tests en segundos |
| **Android** | siempre |
| **iOS** | **opt-in** con `-PenableIosTargets=true` (`mobile-app/shared/build.gradle.kts`) |

**Por qué opt-in:** sin el flag, el build funciona **con solo un JDK**, sin descargar
Kotlin/Native (~300 MB). **El precio** es que IntelliJ avisa «iOS Source Set Used Without an
iOS Target» y que iOS exige recordar el flag. Se prefiere un build que funciona en cualquier
máquina.

## 3.3 Precio, dicho claro

Los comandos del móvil se lanzan **desde `mobile-app/`**, no desde la raíz:

```bash
cd mobile-app && ./gradlew :shared:jvmTest
./scripts/movil-android.sh instalar
```

`mobile-app/gradlew` es una copia del wrapper del monorepo y `mobile-app/gradle` es un
enlace a `../gradle`, así que el catálogo de versiones es el mismo: no hay dos listas de
versiones que se puedan separar (`mobile-app/settings.gradle.kts`).
