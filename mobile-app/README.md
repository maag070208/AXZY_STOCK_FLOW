# mobile-app — App móvil de Almacén Integral (KMP + Compose Multiplatform)

UI **compartida** para Android e iOS, escrita una sola vez, más los dos puntos de
entrada de plataforma. La lógica de negocio **no está aquí**: vive en
[`../stock-domain`](../stock-domain), que es Kotlin puro y lo consumen también el
backend y los tests (ver `PLAN_ALMACEN_INTEGRAL.md` §3.2-bis).

---

## 1. Estructura

Vertical slice, no capas (`PLAN_ALMACEN_INTEGRAL.md` §3.4): todo lo de «capturar
factura» está en `feature/invoices/`, no repartido entre una carpeta `ui/` y otra
`model/` globales. Así se ve de un golpe qué existe y se borra un flujo completo sin
dejar huérfanos.

```txt
mobile-app/
├── build.gradle.kts                     # KMP: jvm + android + iosArm64/iosSimulatorArm64
├── README.md                            # este archivo
├── androidApp/                          # APK Android — BUILD DE GRADLE PROPIO
│   ├── settings.gradle.kts              #   (ver §5: AGP 9 obliga a separarlo)
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── gradlew -> ../../gradlew         #   enlace al wrapper del monorepo
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/dev/axzy/stock/mobile/android/MainActivity.kt
│       └── res/                          # tema, colores, icono adaptativo
├── iosApp/                              # Proyecto Xcode (SwiftUI) — aparte, como en el ejemplo real
│   ├── Configuration/Config.xcconfig
│   └── iosApp/
│       ├── iOSApp.swift
│       ├── ContentView.swift             # puente a MainViewControllerKt
│       └── Info.plist                    # NSCameraUsageDescription
└── src/
    ├── commonMain/kotlin/dev/axzy/stock/mobile/
    │   ├── App.kt                        # navegación y montaje de pantallas
    │   ├── core/
    │   │   ├── theme/                    # Color.kt · Shape.kt · InputTokens.kt · Theme.kt
    │   │   ├── ui/                       # Components.kt · AppButton.kt · AppTextField.kt
    │   │   ├── camera/                   # ⭐ el escáner: BarCodeScannerView (expect),
    │   │   │                             #    ScanMaskOverlay, ScanSession, ScannerPanel
    │   │   ├── data/                     # puertos (interfaces) + modelo de lectura
    │   │   │   └── demo/DemoData.kt      #   datos de ejemplo, borrables de una pieza
    │   │   └── nav/Navigator.kt
    │   └── feature/
    │       ├── auth/                     # LoginScreen          (MVVM simple)
    │       ├── home/                     # HomeScreen           (MVVM simple)
    │       ├── invoices/                 # List · Detail · Capture   (MVI en Capture)
    │       ├── orders/                   # List · Detail        (MVVM simple)
    │       ├── locations/                # List · Content       (MVVM simple)
    │       ├── inventory/                # Search + move/       (MVI: la máquina de estados)
    │       └── labels/                   # Labels · PrintJobs   (MVVM simple)
    ├── androidMain/kotlin/.../core/camera/BarCodeScannerView.android.kt   # CameraX + ML Kit
    ├── iosMain/kotlin/.../core/camera/BarCodeScannerView.ios.kt           # AVFoundation
    ├── iosMain/kotlin/dev/axzy/stock/mobile/MainViewController.kt         # puente a SwiftUI
    ├── jvmMain/kotlin/.../core/camera/BarCodeScannerView.jvm.kt           # stub: el target JVM es para tests
    └── jvmTest/kotlin/                        # 30 tests, sin emulador
        ├── core/camera/ScanSessionTest.kt
        └── feature/inventory/move/MoveStockReducerTest.kt · MoveStockViewModelTest.kt
```

---

## 2. Cómo compilar y probar

```bash
export GRADLE_USER_HOME=/Users/axzy/DEV/STOCK/.gradle-home   # el sandbox bloquea ~/.gradle
export KONAN_DATA_DIR=/Users/axzy/DEV/STOCK/.konan-home      # solo para iOS

# 1. Compilar la UI compartida para JVM (segundos)
./gradlew :mobile-app:compileKotlinJvm --no-daemon --console=plain

# 2. Los tests de la máquina de estados, SIN emulador
./gradlew :mobile-app:jvmTest --no-daemon --console=plain
#    Comprobar SIEMPRE que corrieron de verdad: Gradle dice «BUILD SUCCESSFUL» con
#    0 tests ejecutados. Los resultados están en
#    mobile-app/build/test-results/jvmTest/TEST-*.xml

# 3. Compilar el target Android de la biblioteca compartida
./gradlew :mobile-app:compileAndroidMain --no-daemon --console=plain

# 4. Compilar para iOS (descarga Kotlin/Native la primera vez, ~500 MB)
./gradlew :mobile-app:compileKotlinIosArm64 -PenableIosTargets=true --no-daemon --console=plain
```

APK de Android — **desde su propio directorio**, no desde la raíz (§5):

```bash
cd mobile-app/androidApp
./gradlew :assembleDebug --no-daemon --console=plain
# → mobile-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
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

## 5. Por qué `androidApp` es un build de Gradle aparte

**Con AGP 9.0.1, `com.android.application` y `org.jetbrains.kotlin.multiplatform` no
pueden vivir en el mismo build de Gradle.** En cuanto comparten classpath, Kotlin
intenta crear su `KotlinAndroidTarget` y el build entero falla con:

```txt
Could not generate a decorated class for type KotlinAndroidTarget.
> com/android/build/gradle/api/BaseVariant
```

`com.android.build.gradle.api.BaseVariant` es una clase que **AGP 9 eliminó**. El
fallo no es del módulo del APK: **tumba la configuración de todo el monorepo**,
incluidos `stock-domain`, `backend` y los demás módulos. Por eso el APK se compila en
su propio build (igual que `iosApp` es su propio proyecto de Xcode).

Las cuatro alternativas se probaron y **ninguna funciona** en este repositorio; el
detalle está en [`androidApp/settings.gradle.kts`](androidApp/settings.gradle.kts):

| Intento | Resultado real |
|---|---|
| `apply false` en el `plugins {}` raíz (lo que pide la guía oficial) | «Unresolved reference 'namespace' / 'compileSdk'» en `stock-database` |
| Versiones en `pluginManagement` + `id(...)` sin versión | mismo error de `KotlinAndroidTarget` |
| `android.enableLegacyVariantApi=true` | AGP 9: «the property has no effect, use android.newDsl instead» |
| `org.jetbrains.kotlin.android` en `androidApp` | Gradle no lo encuentra, y AGP 9 ya integra Kotlin |

**Deuda a vigilar:** cuando AGP 10 elimine la API heredada (2026) o cuando el plugin de
KMP soporte `com.android.application`, `androidApp` puede volver a ser un subproyecto
más y desaparecer el segundo build.

### Dos rutas absolutas que hay que arreglar al mover el repo

En `androidApp/gradle.properties` y en el `signingConfigs` de
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
   `code`, nunca con `message` (CONTRATO_API.md §1.3), y los cinco códigos de
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

| Comprobación | Comando | Resultado |
|---|---|---|
| UI compartida en JVM | `:mobile-app:compileKotlinJvm` | ✅ `BUILD SUCCESSFUL` |
| Tests del reducer/ViewModel | `:mobile-app:jvmTest` | ✅ **30 tests, 0 fallos, 0 saltados** (leído del XML, no del «BUILD SUCCESSFUL») |
| Biblioteca para Android | `:mobile-app:compileAndroidMain` | ✅ `BUILD SUCCESSFUL` |
| iOS arm64 | `:mobile-app:compileKotlinIosArm64 -PenableIosTargets=true` | ✅ `BUILD SUCCESSFUL` |
| Framework iOS enlazado | `:mobile-app:linkDebugFrameworkIosSimulatorArm64 -PenableIosTargets=true` | ✅ `MobileApp.framework` generado (3 m 21 s) |
| APK Android | `androidApp/./gradlew :assembleDebug` | ✅ `androidApp-debug.apk` (42 MB, sin minificar) |

Desglose del XML de tests (`mobile-app/build/test-results/jvmTest/TEST-*.xml`):

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
| `androidApp` con su propio build de Gradle | AGP 9 no admite `com.android.application` y el plugin de KMP en el mismo build. Ver §5. | Cuando AGP 10 (2026) o el plugin de KMP lo permitan |
| Versiones de los plugins del móvil repetidas en `settings.gradle.kts` (`pluginManagement`) y en `libs.versions.toml` | Un alias del catálogo rompe la resolución de plugins del monorepo. Ver §5. | Si Gradle permite unificar ambas rutas |
| Rutas absolutas a `/Users/axzy/DEV/STOCK/.gradle-home` en `androidApp/gradle.properties` y en su `signingConfigs` | El sandbox bloquea `~/.android`. Ver §5. | Al mover el repo a otra máquina (borrar y dejar que AGP use el suyo) |
| `jvmMain` con un stub del escáner | Kotlin exige un `actual` por target, y el JVM es el target de los tests. | Nunca: es el precio de poder probar sin emulador |
| `Navigator` propio en vez de `androidx.navigation` | Evita una dependencia más que verificar; cubre 16 destinos sin enlaces profundos. | Al necesitar deep links o el «atrás» del sistema |

