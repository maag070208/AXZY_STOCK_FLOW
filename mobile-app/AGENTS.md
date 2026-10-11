# STOCK mobile app: working rules

The floor app: receiving, locating, picking, scanning. Its structure follows
[ADR-0003](../docs/adr/0003-vertical-slice-en-la-app.md) and
[app-kmp.md](../docs/arquitectura/app-kmp.md). The setup (Gradle, plugins, Compose, theme layout)
is the one of PuertoNuevoApp, the KMP project already in use.

## Layout

```
shared/src/commonMain/
  composeResources/values/strings.xml   every text the user reads
  composeResources/drawable, files/     the brand glyph and the Lottie loader
  kotlin/dev/axzy/stock/mobile/
    App.kt                              the screens and the routes between them
    core/theme/                         colors, shapes, spacing, the Material theme
    core/nav/                           Screen, Navigator, the platform back handler
    core/ui/                            the reusable components, one file each
    feature/<name>/                     one slice per module: UiState, ViewModel, Screen
shared/src/androidMain, shared/src/iosMain   what only one platform has
androidApp/                             the Android host
iosApp/                                 the iOS host (SwiftUI around MainViewController)
```

## Rules

- **Text comes from `strings.xml`.** Screens and components never write a literal the user reads.
  Use `stringResource(Res.string.<key>)`. Keys are grouped by prefix like the web's `en.json`:
  `nav_`, `common_`, `home_`, `receiving_`, `status_`, `catalog_`. Another language is another
  `values-<locale>/strings.xml` with the same keys; the code does not change.
- **Rejections are translated by case, not shown raw.** The domain's message is English and
  technical; the app maps `DomainError` cases to strings (`feature/receiving/ReceivingMessages.kt`).
- **The rules live in `stock-domain`.** The app does not reimplement quantity, state or location rules.
  It checks what the operator typed (text, sign) and asks the domain.
- **UI goes through `core/ui` and `core/theme`.** Screens use `AppScaffold`, `AppCard`, `AppButton`,
  `AppTextField`, `StatusChip`, `AppList` and the states (`LoadingState`, `EmptyState`, `ErrorState`).
  Colors come from `MaterialTheme.colorScheme` and `AppColors` (status only); corners from `AppShape`;
  spacing from `AppSpacing`. No hardcoded colors, radii or sizes in screens.
- **One component per file** in `core/ui`. A component that needs a text takes it from the caller or from
  `common_*` keys; it never embeds a sentence.
- **Vertical slices.** A feature lives in `feature/<name>` and does not import another feature.
  Anything two features need goes to `core/`.
- **MVVM per screen.** `<X>UiState.kt` (immutable data class), `<X>ViewModel.kt` (`StateFlow`, no
  navigation, no text), `<X>Screen.kt` (UI only: reads the state, calls the ViewModel, and reports
  navigation through callbacks).
- **Navigation is explicit.** A screen is a `Screen` value; `App.kt` maps each value to its route and
  `Navigator` holds the stack. A screen that works on data carries only the id.
- **Text in code and docs is English** ([ADR-0018](../docs/adr/0018-todo-en-ingles.md)).

## Verify

Run from `mobile-app/`:

```bash
./gradlew :shared:testAndroidHostTest
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64 -PenableIosTargets=true
./gradlew :androidApp:assembleDebug
```

The iOS host builds with `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp`; its run script
calls Gradle with `-PenableIosTargets=true`.

The Android SDK must be reachable (`ANDROID_HOME`, or `sdk.dir` in `local.properties`, which is not versioned).
