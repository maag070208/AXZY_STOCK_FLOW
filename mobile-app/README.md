# mobile-app — App móvil de Almacén Integral (KMP + Compose Multiplatform)

UI **compartida** para Android e iOS, escrita una sola vez, más los dos puntos de
entrada de plataforma. La lógica de negocio **no está aquí**: vive en
[`../stock-domain`](../stock-domain), que es Kotlin puro y lo consumen también el
backend y los tests (ver `docs/historico/PLAN_ALMACEN_INTEGRAL.md` §3.2-bis).

> **`mobile-app` es un build de Gradle propio.** Se abre y se compila desde este
> directorio, no desde la raíz del monorepo. El porqué y el precio están en §5 y §9.

---

## 1. Estructura

Vertical slice, no capas (`docs/historico/PLAN_ALMACEN_INTEGRAL.md` §3.4): todo lo de «capturar
factura» está en `feature/invoices/`, no repartido entre una carpeta `ui/` y otra
`model/` globales. Así se ve de un golpe qué existe y se borra un flujo completo sin
dejar huérfanos.

```txt
mobile-app/                                # ← RAÍZ DE UN BUILD DE GRADLE
├── settings.gradle.kts                    # include(":shared") + include(":androidApp")
│                                          #   + includeBuild("..") para el dominio
├── build.gradle.kts                       # los SEIS plugins con `apply false`
├── gradle.properties                      # heap, metaspace y keystore de depuración
├── gradlew                                # COPIA del wrapper del monorepo (ver §9)
├── gradle -> ../gradle                    # enlace: mismo wrapper y MISMO catálogo
├── local.properties                       # sdk.dir (no se versiona)
├── README.md                              # este archivo
├── shared/                                # ← LA BIBLIOTECA KMP (era mobile-app/src)
│   ├── build.gradle.kts                   #   jvm + android + iosArm64/iosSimulatorArm64
│   └── src/
│       ├── commonMain/kotlin/dev/axzy/stock/mobile/
│       │   ├── App.kt                     # navegación y montaje de pantallas
│       │   ├── core/
│       │   │   ├── theme/                 # Color.kt · Shape.kt · InputTokens.kt · Theme.kt
│       │   │   ├── ui/                    # Components.kt · AppButton.kt · AppTextField.kt
│       │   │   ├── camera/                # ⭐ el escáner: BarCodeScannerView (expect),
│       │   │   │                          #    ScanMaskOverlay, ScanSession, ScannerPanel
│       │   │   ├── data/                  # puertos (interfaces) + modelo de lectura
│       │   │   │   └── demo/DemoData.kt   #   datos de ejemplo, borrables de una pieza
│       │   │   └── nav/Navigator.kt
│       │   └── feature/
│       │       ├── auth/                  # LoginScreen          (MVVM simple)
│       │       ├── home/                  # HomeScreen           (MVVM simple)
│       │       ├── invoices/              # List · Detail · Capture   (MVI en Capture)
│       │       ├── orders/                # List · Detail        (MVVM simple)
│       │       ├── locations/             # List · Content       (MVVM simple)
│       │       ├── inventory/             # Search + move/       (MVI: la máquina de estados)
│       │       └── labels/                # Labels · PrintJobs   (MVVM simple)
│       ├── androidMain/kotlin/.../core/camera/BarCodeScannerView.android.kt  # CameraX + ML Kit
│       ├── iosMain/kotlin/.../core/camera/BarCodeScannerView.ios.kt          # AVFoundation
│       ├── iosMain/kotlin/dev/axzy/stock/mobile/MainViewController.kt        # puente a SwiftUI
│       ├── jvmMain/kotlin/.../core/camera/BarCodeScannerView.jvm.kt          # stub: el JVM es para tests
│       └── jvmTest/kotlin/                    # 30 tests, sin emulador
│           ├── core/camera/ScanSessionTest.kt
│           └── feature/inventory/move/MoveStockReducerTest.kt · MoveStockViewModelTest.kt
├── androidApp/                            # ← EL APK (submódulo normal, SIN settings propio)
│   ├── build.gradle.kts                   #   com.android.application + project(":shared")
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/dev/axzy/stock/mobile/android/MainActivity.kt
│       └── res/                           # tema, colores, icono adaptativo
└── iosApp/                                # Proyecto Xcode (SwiftUI) — aparte, como en el ejemplo real
    ├── Configuration/Config.xcconfig
    └── iosApp/
        ├── iOSApp.swift
        ├── ContentView.swift              # puente a MainViewControllerKt
        └── Info.plist                     # NSCameraUsageDescription
```

**El paquete Kotlin no cambió**: sigue siendo `dev.axzy.stock.mobile`. La
reestructuración movió directorios, no código: ningún archivo `.kt` cambió de
contenido, y ninguna ruta de `package` ni ningún `import` se tocó.

---

## 2. Cómo compilar y probar

**Todo desde `mobile-app/`**, que es la raíz del build:

```bash
cd mobile-app
export GRADLE_USER_HOME=/Users/axzy/DEV/STOCK/.gradle-home   # el sandbox bloquea ~/.gradle
export KONAN_DATA_DIR=/Users/axzy/DEV/STOCK/.konan-home      # solo para iOS

# 1. Compilar la UI compartida para JVM (segundos)
./gradlew :shared:compileKotlinJvm --no-daemon --console=plain

# 2. Los tests de la máquina de estados, SIN emulador
./gradlew :shared:jvmTest --no-daemon --console=plain
#    Comprobar SIEMPRE que corrieron de verdad: Gradle dice «BUILD SUCCESSFUL» con
#    0 tests ejecutados. Los resultados están en
#    mobile-app/shared/build/test-results/jvmTest/TEST-*.xml

# 3. Compilar el target Android de la biblioteca compartida
./gradlew :shared:compileAndroidMain --no-daemon --console=plain

# 4. Compilar para iOS (descarga Kotlin/Native la primera vez, ~500 MB)
./gradlew :shared:compileKotlinIosArm64 -PenableIosTargets=true --no-daemon --console=plain
#    ⚠️ `-PenableIosTargets=true` NO es opcional y no se puede omitir.
#    Los targets de iOS son OPT-IN tanto aquí como en `stock-domain`, y con
#    `includeBuild` los DOS tienen que estar de acuerdo: si `shared` declarara
#    targets de iOS y `stock-domain` no, Gradle no encontraría variante
#    («No matching variant of project :STOCK:stock-domain ... 'native'»).
#    La propiedad se pasa UNA vez y la ven los dos builds. El porqué completo,
#    y los dos intentos de propagarla automáticamente que NO funcionaron, están
#    en el comentario del bloque `kotlin {}` de `shared/build.gradle.kts`.

# 5. El APK
./gradlew :androidApp:assembleDebug --no-daemon --console=plain
# → mobile-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

La tarea del modelo de build que pide IntelliJ también resuelve desde aquí, que es
lo que hace que el IDE sincronice:

```bash
./gradlew :prepareKotlinBuildScriptModel --no-daemon --console=plain   # BUILD SUCCESSFUL
```

iOS: abrir `mobile-app/iosApp/iosApp.xcodeproj` en Xcode. El proyecto tiene una
fase de build que llama a Gradle para generar y enlazar el framework `MobileApp`.

> **`TEAM_ID` vacío en `Configuration/Config.xcconfig` es intencional**: sin equipo de
> firma, el proyecto compila y corre en simulador, que es donde se valida la UI.
> Poner un identificador de equipo ajeno rompería la compilación de los demás.

---

## 3. El escáner: `ScanWindow` y `ScanState` del dominio

Este es el punto donde el legacy fallaba y donde el móvil tenía que **no repetir el
error**.

### 3.1 El bug M4: dos definiciones de la misma ventana

El escáner del legacy tenía la **máscara visual de 300×100** y la **validación de
250×50**, en dos archivos distintos. El operador centraba el código en la banda que
veía y el escáner lo descartaba sin explicar por qué.

La causa raíz no era el tamaño: era que había **dos definiciones**. Aquí hay una
sola, y es la del dominio:

```kotlin
// stock-domain — la ÚNICA definición de la ventana
val ScanWindow.DEFAULT = ScanWindow(
    window = ScanRect(left = 0.10, top = 0.35, width = 0.80, height = 0.20),
    tolerance = 0.05,
)
```

Y las dos cosas que antes eran dos números ahora leen **el mismo objeto**:

| Qué | Dónde | Cómo usa la ventana |
|---|---|---|
| Máscara que se pinta | `commonMain/core/camera/ScanMaskOverlay.kt` | dibuja `window.window` (marco continuo) y `window.effective` (marco discontinuo = tolerancia) |
| Validación del escaneo | `stock-domain/…/scanner/ScanModel.kt` → `ScanWindow.contains` | comprueba el **centro** del código contra `window.effective` |

`ScanMaskOverlay` está en `commonMain` **a propósito**: si se dibujara en cada
plataforma por separado, volvería a haber dos definiciones y volverían a
desalinearse, esta vez sin que ningún test lo note (cada plataforma sería coherente
consigo misma).

El rectángulo viaja **normalizado (0..1)**, no en píxeles: con píxeles, la misma
ventana lógica mediría cosas distintas en un teléfono y en una tablet, que es
literalmente por lo que el legacy fallaba.

### 3.2 El bug M3: el `step` numérico

El legacy usaba un `step` que se incrementaba una vez y un handler que comparaba
`step === 1`, así que la rama que asignaba la ubicación **era inalcanzable**.

Aquí el estado es un tipo sellado del dominio y las transiciones las hace
`ScanState.onScan`, **en `stock-domain`**:

```txt
Idle ──escanea producto──▶ ProductScanned ──unidades──▶ (mismo estado, units != null)
                                          ──escanea ubicación──▶ ReadyToConfirm
```

- Las transiciones inválidas **no se pueden escribir**: `ProductScanned` es el único
  estado que lleva el producto, así que no existe forma de asignar una ubicación sin
  haber escaneado el producto antes.
- El `step` que se muestra en pantalla sale de `state.step` **del dominio**, no de un
  contador local. Un test verifica que `uiState.step == scanState.step` en cada paso.
- El backend ejecuta el **mismo** `onScan`, así que móvil y servidor no pueden
  divergir.

### 3.3 Quién hace qué

```txt
BarCodeScannerView (expect)          ← cámara: CameraX+ML Kit / AVFoundation
        │  entrega ScanInput(raw, bounds) — SIN validar nada
        ▼
ScanSession / reduceMoveStock        ← filtro de repetición + llama a onScan
        │  ScanOutcome.Advanced / Rejected
        ▼
ScanState.onScan  (stock-domain)     ← LA transición, compartida con el backend
        │
        ▼
ScanMaskOverlay                      ← pinta la MISMA ventana que validó
```

**Las plataformas no validan la posición.** En cuanto una valide por su cuenta,
vuelve a haber dos ventanas y el bug M4 regresa.

### 3.4 Tests que lo inmovilizan (en JVM, sin emulador)

| Test | Qué fija |
|---|---|
| `MoveStockReducerTest` › «el flujo producto → unidades → ubicación llega a ReadyToConfirm» | el flujo de dos pasos **llega al final** (bug M3) |
| `MoveStockReducerTest` › «un código en el punto visible MÁS ALTO de la banda se acepta» | el borde de la ventana **visual** se acepta, y sigue habiendo límite (bug M4) |
| `MoveStockReducerTest` › «la ventana que se dibuja es la que valida» | recorre la ventana en rejilla; si máscara y validación se separan, **falla** |
| `ScanSessionTest` › «la marca de posición usa la MISMA ventana que la validación» | el marco verde nunca miente al operador |
| `ScanThrottleTest` › «un código distinto pasa aunque sea inmediato» | el filtro de repetición no rompe el paso 2 del flujo |

---

## 4. Design system: por qué no hay `Modifier` sueltos

Regla del proyecto (heredada del `AGENTS.md` del ejemplo real): **todo** elemento de
UI usa los componentes de `core/ui` y los tokens de `core/theme`. Está prohibido
reimplementar estilos locales con `Modifier` sueltos.

| Necesidad | Componente / token | Prohibido |
|---|---|---|
| Tarjeta, fila, tile | `AppCard`, `AppRowCard`, `AppSurfaceCard`, `AppActionCard` | `background + border + clickable` a mano |
| Botón | `AppButton(variant = Primary/Secondary/Text)`, `AppDangerButton` | `Button`/`OutlinedButton` directos |
| Campo de texto | `AppTextField`, `AppPasswordField`, `AppSearchField` | `OutlinedTextField`, `BasicTextField` |
| Chip de estado | `StatusChip`, `DomainStatusChip` | `Box` con fondo y padding propios |
| Filtro | `AppFilterChip` | pills clicables locales |
| Modal | `AppModal` | `AlertDialog` propio |
| Desplegable | `AppDropdownField` | `DropdownMenu` con `Surface` local |
| Radios | `AppShape.card/row/icon/pill/field` | `RoundedCornerShape(<n>.dp)` en pantalla |
| Colores | `MaterialTheme.colorScheme.*` + `AppColors` | literales `Color(0xFF…)` en pantalla |

Dos detalles que son la causa de la mitad de los defectos visuales:

1. **`.clip(shape)` va ANTES de `.background(...)` y `.clickable(...)`**. En el orden
   inverso el ripple se dibuja cuadrado sobre una tarjeta redondeada.
2. **Un solo botón primario por pantalla.** `AppButton` deshabilita el botón mientras
   `loading` es `true`: sin eso, dos pulsaciones rápidas en «Confirmar movimiento» son
   dos movimientos de inventario.

El único color duplicado del proyecto está en
`androidApp/src/main/res/values/colors.xml`, y es inevitable: ese archivo lo lee
Android **antes** de que Compose exista (es el fondo de ventana).

---

## 5. Por qué `mobile-app` es un build de Gradle propio

Este módulo tiene su **propio `settings.gradle.kts`**, su propio `build.gradle.kts`
raíz y sus dos submódulos `:shared` y `:androidApp`. Es la misma forma que el
proyecto de referencia `PuertoNuevoApp`, y sustituye al esquema anterior —un
submódulo del monorepo con un segundo build de Gradle anidado dentro— que hacía que
**IntelliJ no pudiera abrirlo en solitario**:

```txt
Task 'prepareKotlinBuildScriptModel' not found in project ':mobile-app'
```

Ese error salía porque la carpeta que se abría no era la raíz de ningún build de
Gradle. Ahora sí lo es:

```bash
cd mobile-app && ./gradlew :prepareKotlinBuildScriptModel   # BUILD SUCCESSFUL
```

### Por qué `:androidApp` ya NO necesita su propio build

El esquema anterior separaba el APK en un build aparte porque se creía que
`com.android.application` y `org.jetbrains.kotlin.multiplatform` no podían convivir.
**Con AGP 9 sí pueden**, y el detalle importa porque es fácil confundir dos errores
distintos:

| Plugin | Qué hace | ¿Compatible con KMP? |
|---|---|---|
| `com.android.library` / `com.android.application` | DSL clásico, crea un `KotlinAndroidTarget` que referencia `com.android.build.gradle.api.BaseVariant` | ✗ — esa clase la borró AGP 9 |
| `com.android.kotlin.multiplatform.library` | Declara el target de Android **dentro de `kotlin {}`**; no pasa por `KotlinAndroidTarget` | ✓ — es el plugin que AGP 9 creó para esto |

`shared` aplica el tercero y `androidApp` el primero, y **pueden estar en el mismo
build** porque el que rompía (`KotlinAndroidTarget`) ya no se instancia. Eso es todo
lo que hacía falta para que `:shared:jvmTest` y `:androidApp:assembleDebug` se lancen
desde el mismo `./gradlew`.

### El dominio entra como BUILD INCLUIDO, no como submódulo

`stock-domain` **no se copia ni se duplica**: sería tener dos definiciones de las
reglas de negocio, que es justo lo que el módulo compartido existe para evitar. Se
consume con `includeBuild("..")` en `settings.gradle.kts`, que es la forma canónica de
Gradle de componer dos builds:

```kotlin
includeBuild("..") {
    dependencySubstitution {
        substitute(module("dev.axzy.stock:stock-domain")).using(project(":stock-domain"))
    }
}
```

Dos detalles que costaron un intento cada uno y quedan documentados:

1. **La dependencia se declara por COORDENADA**, no con `project(":stock-domain")`.
   La composición de builds solo sustituye dependencias de **módulo**; un
   `project(":...")` se resuelve dentro del build que lo escribe y no cruza la
   frontera («Project with path ':stock-domain' could not be found»).
2. **Por eso el build del monorepo fija `group`** para todos sus módulos en
   `../settings.gradle.kts`. Sin grupo, la coordenada sale como `stock-domain` a
   secas y Gradle la rechaza («Cannot convert the provided notation to an object of
   type ComponentSelector»). Es la **única** línea que se tocó fuera de
   `mobile-app/`.

Y una consecuencia que hay que saber: `mobile-app` **ya no está** en el
`settings.gradle.kts` del monorepo. Un directorio no puede ser raíz de dos builds, y
además el monorepo veía `:mobile-app` como un proyecto suelto sin sus submódulos
(`:mobile-app:shared:jvmTest` → «project 'shared' not found»). Los comandos del móvil
se lanzan desde `mobile-app/`.

### Dos rutas absolutas que hay que arreglar al mover el repo

En `gradle.properties` y en el `signingConfigs` de
`androidApp/build.gradle.kts` hay rutas **absolutas** a
`/Users/axzy/DEV/STOCK/.gradle-home`. Están así porque el sandbox de este entorno
bloquea `~/.android`, donde AGP quiere crear el keystore de depuración. En una máquina
normal se pueden borrar las dos y dejar que AGP use el suyo.

---

## 6. Qué falta para completar la Fase 6

Hecho en esta entrega:

- [x] Scaffold KMP + Compose Multiplatform (Android, iOS y JVM para tests).
- [x] Tema y design system Compose (`core/theme`, `core/ui`).
- [x] `composeApp` por vertical slice, una carpeta por caso de uso.
- [x] Escáner `expect/actual` con la **ventana de validación compartida** desde
      `commonMain` (bug M4 cubierto por test).
- [x] Patrón por pantalla según §3.4: MVI con intents sellados en el escáner y el
      movimiento; MVVM simple en CRUD y listados.
- [x] **Tests del reducer y del ViewModel pasan en JVM sin emulador** (30 tests).
- [x] Los dos puntos de entrada (`androidApp` genera APK; `iosApp` compila el
      framework).

Falta, en orden de dependencia:

1. **`stock-network`** — es el bloqueante de casi todo (§7). Sin él no hay sesión real,
   ni permisos, ni datos del servidor.
2. **`stock-database`** (SQLDelight) — la captura **sin red** y la cola de
   sincronización con idempotencia por `client_uuid`. Es el criterio de aceptación más
   exigente de la Fase 6 y no se puede cumplir con datos en memoria.
3. **`stock-datastore`** — `serverUrl` configurable y preferencias. Falta la pantalla
   `feature/config` (la del legacy `ConfigScreen`).
4. **Navegación real** — `Navigator` es una pila en memoria; sin enlaces profundos ni
   el «atrás» del sistema. Cuando lleguen, se sustituye **esa clase** y ninguna
   pantalla cambia, porque las pantallas no navegan: emiten.
5. **Pantallas que faltan del inventario de 26** — conteo físico (pantalla nueva del
   plan), traslados entre bodegas, reportes, `AssignLocation` de factura, y el
   `feature/config`.
6. **Cámara en iOS probada en dispositivo** — compila y la conversión de coordenadas
   está escrita con su razonamiento, pero **no se ha ejecutado en un iPhone** (ver §8).
7. **Koin** para la inyección: hoy es manual (`AppRepositories`), que es suficiente
   para dos dependencias.
8. **Kardex y detalle de producto en el móvil** — el puerto existe
   (`InventoryRepository.kardexForProduct`), falta la pantalla.

---

## 7. Qué se necesita de `stock-network` (firmas exactas)

Los puertos ya están definidos en `core/data/Repositories.kt`; la integración es
**implementarlos** y cambiar una línea en `App.kt`
(`DemoData.repositories` → `NetworkRepositories`). Firmas que hacen falta:

```kotlin
// ── Autenticación ───────────────────────────────────────────────────────────
suspend fun login(username: String, password: String): LoginResult
//   LoginResult = Ok(accessToken, refreshToken, expiresInSeconds, user) | Error(ApiErrorCode)
//   ApiErrorCode debe distinguir INVALID_CREDENTIALS, ACCOUNT_DISABLED,
//   ACCOUNT_LOCKED (con details.until) y RATE_LIMITED: la interfaz dice algo
//   distinto en cada caso y colapsarlos en «no autorizado» es el defecto del legacy.

suspend fun refresh(): LoginResult            // 401 TOKEN_REUSED → descartar sesión
suspend fun logout(): Unit                    // idempotente, tolera 401
suspend fun me(): UserSession

// ── Catálogo ────────────────────────────────────────────────────────────────
suspend fun products(page: Int, size: Int = 50, q: String? = null): Paged<ProductDto>
suspend fun productByCode(code: String): ProductDto?
suspend fun locations(page: Int = 0, size: Int = 50): Paged<LocationDto>
suspend fun locationByCode(code: String): LocationDto?
suspend fun locationContent(locationId: Long, page: Int = 0): Paged<StockLevelDto>

// ── Inventario ──────────────────────────────────────────────────────────────
suspend fun stock(productId: Long? = null, locationId: Long? = null): Paged<StockLevelDto>
suspend fun kardex(productCode: String, from: String? = null, to: String? = null): Paged<KardexEntryDto>
suspend fun moveStock(
    productId: Long, fromLocationId: Long, toLocationId: Long,
    quantity: Double, idempotencyKey: String,        // OBLIGATORIA: sin ella un reintento duplica
): MoveResult

suspend fun locate(
    invoiceLineId: Long, toLocationId: Long, quantity: Double, idempotencyKey: String,
): LocateResult

// ── Recepción ───────────────────────────────────────────────────────────────
suspend fun invoices(page: Int = 0): Paged<InvoiceDto>
suspend fun invoice(id: Long): InvoiceDetailDto          // con `lines`
suspend fun captureLine(
    invoiceId: Long, lineId: Long, physicalQuantity: Double,
    physicalBoxQuantity: Double?, idempotencyKey: String,
): CaptureResult
//   `difference` y el `status` de la línea los DEVUELVE el servidor.
//   El móvil NO debe recalcularlos para decidir: el contrato los declara columna
//   generada para que no puedan desincronizarse.

// ── Surtido y etiquetas ─────────────────────────────────────────────────────
suspend fun orders(page: Int = 0): Paged<OrderDto>
suspend fun order(id: Long): OrderDetailDto
suspend fun confirmOrder(id: Long, idempotencyKey: String): ConfirmResult
suspend fun labelTemplates(): List<LabelTemplateDto>
suspend fun previewLabel(templateId: Long, data: Map<String, String>): LabelPreviewDto
suspend fun printLabel(templateId: Long, copies: Int, data: List<Map<String, String>>): PrintJobResult
suspend fun printJobs(status: String? = null): List<PrintJobDto>
```

**Lo que `stock-network` debe resolver y el móvil necesita que sea explícito:**

1. **`ApiError.code` como enum sellado**, no como `String`. El cliente decide con
   `code`, nunca con `message` (docs/historico/CONTRATO_API.md §1.3), y los cinco códigos de
   autorización exigen **cinco acciones distintas** del usuario:
   `UNAUTHENTICATED` → entrar; `PERMISSION_DENIED` → pedir permiso;
   `SCOPE_INSUFFICIENT` → pedir acceso a esa bodega; `POLICY_DENIED` → cambiar el
   cómo/cuándo; `SEGREGATION_OF_DUTIES` → que lo apruebe otra persona.
2. **Cabeceras automáticas**: `Authorization`, `X-Request-Id`,
   `X-Client-Channel: MOBILE` (o `MOBILE_OFFLINE`), `X-Client-Timezone`.
3. **`Idempotency-Key` en la firma, no opcional** — es lo que hace que un reintento
   sin señal no duplique un movimiento. Ya está así en los puertos de este módulo.
4. **Renovación de token transparente en el 401**, y al recibir `TOKEN_REUSED`
   descartar TODA la sesión y pedir credenciales.
5. **Paginación tipada** (`Paged<T>` con `items/page/size/totalItems/totalPages`).
   Con 1.398 productos, cargar la tabla entera es el defecto del legacy.
6. **Los totales de producto y los contadores vienen del servidor**: `ProductDto.stock`
   y `InvoiceDto.pendingLines`/`locatedLines` no se calculan en el móvil.

---

## 8. Estado de verificación

Salida real ejecutada en esta máquina (macOS, JDK 17, Kotlin 2.4.10, AGP 9.0.1,
Gradle 9.1.0). El resumen está aquí; la transcripción literal está en la entrega.

| Comprobación | Comando (desde `mobile-app/`) | Resultado |
|---|---|---|
| UI compartida en JVM | `:shared:compileKotlinJvm` | ✅ `BUILD SUCCESSFUL` |
| Tests del reducer/ViewModel | `:shared:jvmTest` | ✅ **30 tests, 0 fallos, 0 saltados** (leído del XML, no del «BUILD SUCCESSFUL») |
| Biblioteca para Android | `:shared:compileAndroidMain` | ✅ `BUILD SUCCESSFUL` |
| iOS arm64 | `:shared:compileKotlinIosArm64 -PenableIosTargets=true` | ✅ `BUILD SUCCESSFUL` |
| Framework iOS enlazado | `:shared:linkDebugFrameworkIosSimulatorArm64 -PenableIosTargets=true` | ✅ `MobileApp.framework` generado (3 m 21 s) |
| APK Android | `:androidApp:assembleDebug` | ✅ `androidApp-debug.apk` (42 MB, sin minificar) |
| **Sincronización del IDE** | `:prepareKotlinBuildScriptModel` | ✅ `BUILD SUCCESSFUL` — es la tarea que pedía IntelliJ y la que antes fallaba |

Desglose del XML de tests (`mobile-app/shared/build/test-results/jvmTest/TEST-*.xml`
— la ruta cambió con la reestructuración):

```txt
ScanSessionTest            tests= 7 failures=0 errors=0 skipped=0
ScanThrottleTest           tests= 5 failures=0 errors=0 skipped=0
MoveStockReducerTest       tests=12 failures=0 errors=0 skipped=0
MoveStockViewModelTest     tests= 6 failures=0 errors=0 skipped=0
TOTAL tests=30 failures=0 errors=0 skipped=0
```

**Lo que NO está verificado en esta máquina, y por qué:**

- **`iosApp` no se ha abierto ni compilado con Xcode.** Lo que sí está verificado es
  que el **framework de Kotlin** compila y enlaza para iOS, que es lo que valida el
  código compartido y el `actual` de AVFoundation. Lo que **no** está probado es el
  `pbxproj` (escrito a mano, sin abrirlo en Xcode) ni la **cámara en un iPhone**. La
  conversión de coordenadas del `actual` de iOS está razonada y comentada, pero no
  ejecutada en dispositivo: es la primera cosa que hay que probar en un iPhone real.
- **Nada se ha ejecutado en un emulador o dispositivo Android.** El APK se genera; no
  se ha instalado ni arrancado. El escáner Android (CameraX + ML Kit) **no se ha
  probado con una cámara real**.
- **`stock-database` no compila**: le falta la dependencia `kotlinx-datetime` en su
  `build.gradle.kts` (error `Unresolved reference 'datetime'` en
  `ColaSincronizacion.kt`). Es de otro agente y **no se ha tocado**.
- **`stock-network` no compila**: tiene errores de sintaxis (`Syntax error: Unclosed
  comment`) en varios DTOs, de otro agente en paralelo. No se ha tocado.
- **La cámara real, la sincronización sin red y la idempotencia de extremo a extremo
  no se pueden verificar todavía**: dependen de `stock-network` y de un dispositivo.

### Deuda asumida en esta entrega, con su motivo

| Deuda | Por qué se asumió | Cuándo se paga |
|---|---|---|
| Kotlin 2.4.10 en TODO el monorepo (estaba en 2.1.20) | Compose Multiplatform 1.11.1 exige Kotlin ≥ 2.2.0 y Gradle no admite dos versiones del plugin en el mismo build. Verificado que `stock-domain` compila y pasa sus **299 tests** con 2.4.10. | Ya está pagada; era obligatoria para que el móvil compile |
| Versiones de los plugins del móvil repetidas en `settings.gradle.kts` (`pluginManagement`) y en `libs.versions.toml` | Los alias del catálogo `apply false` en la raíz del build del móvil funcionan, pero la versión sigue declarada en los dos sitios. Se unificó lo que se pudo: `shared` y `androidApp` usan `alias(...)` o `id(...)` sin versión. | Si Gradle permite unificar ambas rutas |
| `mobile-app` fuera del `settings.gradle.kts` del monorepo | Un directorio no puede ser raíz de dos builds de Gradle, y el monorepo veía `:mobile-app` como un proyecto suelto sin sus submódulos. Ver §5. | No se paga: es la forma correcta de tener un build compuesto |
| La bandera `-PenableIosTargets=true` sigue haciendo falta para iOS | Los targets de iOS de `stock-domain` son OPT-IN por diseño (no se puede cambiar sin tocar ese módulo). `shared` los tiene por defecto. | Si `stock-domain` cambia su default a ON |
| Rutas absolutas a `/Users/axzy/DEV/STOCK/.gradle-home` en `gradle.properties` y en el `signingConfigs` de `androidApp` | El sandbox bloquea `~/.android`. Ver §5. | Al mover el repo a otra máquina (borrar y dejar que AGP use el suyo) |
| `jvmMain` con un stub del escáner | Kotlin exige un `actual` por target, y el JVM es el target de los tests. | Nunca: es el precio de poder probar sin emulador |
| `Navigator` propio en vez de `androidx.navigation` | Evita una dependencia más que verificar; cubre 16 destinos sin enlaces profundos. | Al necesitar deep links o el «atrás» del sistema |

---

## 9. Por qué `apply false` en la raíz del MONOREPO rompía `stock-domain` y `stock-database`

Ésta era la pieza que no estaba entendida, y ahora está reproducida y medida. La
guía oficial de migración a AGP 9 pide declarar los plugins en el `plugins {}` de la
raíz con `apply false`; hacerlo en la raíz del monorepo rompía módulos que nadie
tocó. En la raíz de `mobile-app` **funciona** (`mobile-app/build.gradle.kts`), y la
diferencia es lo que explica el misterio.

### El síntoma, literal

Añadiendo a `build.gradle.kts` de la raíz del monorepo:

```kotlin
alias(libs.plugins.android.kmp.library) apply false
```

y ejecutando `./gradlew :stock-database:tasks`, la configuración falla **al compilar
el script** de `stock-database`:

```txt
e: stock-database/build.gradle.kts:46:5: 'fun android(name: String = ..., configure: KotlinAndroidTarget.() -> Unit = ...): KotlinAndroidTarget'
                                  is deprecated. Please use androidTarget() instead.
e: stock-database/build.gradle.kts:47:9: Unresolved reference 'namespace'.
e: stock-database/build.gradle.kts:48:9: Unresolved reference 'compileSdk'.
e: stock-database/build.gradle.kts:49:23: Unresolved reference 'release'.
e: stock-database/build.gradle.kts:51:9: Unresolved reference 'minSdk'.
```

Es decir: dentro de `kotlin {}`, `android { }` se resuelve como la función
**`android()` obsoleta que devuelve un `KotlinAndroidTarget`** —y esa clase no tiene
`namespace` ni `compileSdk`— en lugar de la extensión de AGP 9, que sí los tiene.

### La causa

AGP 9 se distribuye como **UN solo artefacto** (`gradle-9.0.1.jar`) que contiene los
descriptores de **todos** sus plugins:

```txt
$ unzip -l gradle-9.0.1.jar | grep META-INF/gradle-plugins
com.android.application.properties
com.android.library.properties
com.android.kotlin.multiplatform.library.properties      ← el que importa
...
```

Cuando un plugin se declara en el `plugins {}` de un proyecto —aunque sea con
`apply false`— **sus clases entran en el classpath de script de ese proyecto y de sus
subproyectos**. Al declarar `android-kmp-library` (o cualquier otro plugin de AGP) en
la raíz del monorepo, las clases de AGP 9 quedan visibles para `stock-domain`,
`stock-database` y el resto. Y en ese classpath hay ahora **dos** `android { }`
candidatos dentro de `kotlin {}`:

| Candidato | De dónde sale | Qué expone |
|---|---|---|
| `android()` sobre `KotlinTargetContainerWithPresetFunctions` | API heredada de KMP (sigue en Kotlin 2.4.10) | `KotlinAndroidTarget`: `namespace` ✗, `compileSdk` ✗ |
| Extensión de AGP 9 para KMP | Clases de AGP 9 en el classpath | `namespace` ✓, `compileSdk { }` ✓, `minSdk` ✓ |

Kotlin resuelve **el miembro de la clase receptora antes que la extensión externa**,
así que gana el `android()` heredado. El mensaje «is deprecated. Please use
`androidTarget()` instead» es la pista: el compilador está usando la API vieja.

Y el fallo no se ve en `mobile-app` porque allí el plugin **se aplica** (crea la
extensión de AGP 9 en el proyecto) y porque en el esquema anterior `mobile-app`
resolvía la versión por `pluginManagement`, sin poner las clases en el classpath del
script.

### La medición que lo aísla

Se probó cada plugin por separado en el `plugins {}` de la raíz del monorepo:

| Declarado en la raíz con `apply false` | Resultado en `:stock-database:tasks` |
|---|---|
| Solo `com.android.kotlin.multiplatform.library` | ❌ «Unresolved reference 'namespace'» (el error de arriba) |
| Solo `com.android.application` | ❌ otro error distinto: «Error resolving plugin [id: 'com.android.kotlin.multiplatform.library', version: '9.0.1'] > The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version» |
| Ninguno de los dos | ✅ `BUILD SUCCESSFUL` |

Los dos fallos confirman el mecanismo: **basta declarar UN plugin de AGP 9 en la raíz
para que el artefacto entero de AGP 9 entre en el classpath de todos los
subproyectos.** El segundo error es la prueba directa —Gradle dice literalmente que el
plugin «ya está en el classpath»— y el primero es la consecuencia de que esté.

### Por qué en `mobile-app` sí funciona

Porque allí el `plugins {}` con `apply false` está en la raíz **del propio build**, y
los dos módulos que contiene (`shared` y `androidApp`) **aplican** los plugins, así que
la extensión de AGP 9 existe en sus proyectos. No hay ningún módulo que use el bloque
`android {}` del plugin de KMP sin aplicar `android-kmp-library`. En el monorepo sí
los hay: `stock-domain` y `stock-database` aplican el plugin de KMP, y su `android {}`
depende de que las clases de AGP 9 no estén contaminando el classpath del script.

### La regla que se queda

> Un plugin de AGP 9 declarado con `apply false` contamina el classpath de script de
> **todos** los subproyectos del build. Si algún submódulo usa el bloque `android {}`
> del plugin de KMP y **no** aplica ese plugin de AGP, la declaración lo rompe.

Por eso el `pluginManagement` del monorepo fija las versiones del móvil sin meter las
clases en el classpath, y ahora que `mobile-app` es un build aparte la cuestión ya no
se cruza: cada build declara lo suyo. `mobile-app` puede usar el patrón idiomático
(`apply false` en su raíz + `alias`/`id` en sus módulos) y el monorepo conserva el
suyo.

