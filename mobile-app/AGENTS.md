# STOCK mobile app: working rules

The floor app: receiving, locating, picking, scanning. Its structure follows
[ADR-0003](../docs/adr/0003-vertical-slice-en-la-app.md) and
[app-kmp.md](../docs/arquitectura/app-kmp.md). The setup (Gradle, plugins, Compose, theme layout)
is the one of PuertoNuevoApp, the KMP project already in use.

## Rules

- **The rules live in `stock-domain`.** The app does not reimplement quantity, state or location
  rules. It checks what the operator typed (text, sign) and asks the domain.
- **UI goes through `core/ui` and `core/theme`.** Screens use `AppTextField` (never `OutlinedTextField`
  directly), `MaterialTheme.colorScheme.*`, and `AppColors` for status only. No hardcoded colors in screens.
- **Vertical slices.** A feature lives in `feature/<name>` and does not import another feature.
  Anything two features need goes to `core/`.
- **MVVM per screen.** `<X>UiState.kt` (immutable data class), `<X>ViewModel.kt` (`StateFlow`, no
  navigation), `<X>Screen.kt` (UI only, reads the state and calls the ViewModel).
- **Text is in English** ([ADR-0018](../docs/adr/0018-todo-en-ingles.md)), including code comments.

## Verify

Run from `mobile-app/`:

```bash
./gradlew :shared:testAndroidHostTest
./gradlew :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosSimulatorArm64 -PenableIosTargets=true
./gradlew :androidApp:assembleDebug
```

The Android SDK must be reachable (`ANDROID_HOME`, or `sdk.dir` in `local.properties`, which is not versioned).
