# STOCK mobile app: working rules

The floor app: receiving, locating, picking, scanning. Its structure follows
[ADR-0003](../docs/adr/0003-vertical-slice-en-la-app.md) and
[app-kmp.md](../docs/arquitectura/app-kmp.md). The setup (Gradle, plugins, Compose, theme layout)
is the one of PuertoNuevoApp, the KMP project already in use.

## Layout

```
shared/src/commonMain/
  composeResources/values/strings.xml      every text the user reads, in Spanish (the default)
  composeResources/values-en/strings.xml   the same keys in English
  composeResources/drawable, files/        the brand glyph and the Lottie loader
  kotlin/dev/axzy/stock/mobile/
    App.kt                              the theme, the API client, the session: sign-in screen or the shell
    AppShell.kt                         behind the sign-in: the drawer, the tab bar and the route of every screen
    core/theme/                         colors, shapes, spacing, the Material theme
    core/nav/                           Screen, Navigator, the drawer, the tab bar, back, per-screen ViewModels
    core/ui/                            the reusable components, one file each
    core/network/                       ApiClient, ApiFailure / ApiResult, Loaded, the API's address
    core/session/                       Session: sign-in, renewal, sign-out; SessionUser
    core/receiving/                     what three features share: the repository, the invoice model, the rows
    feature/<name>/                     one slice per module: UiState, ViewModel, Screen
shared/src/androidMain, shared/src/iosMain   what only one platform has
androidApp/                             the Android host
iosApp/                                 the iOS host (SwiftUI around MainViewController)
```

## Rules

- **Text comes from `strings.xml`.** Screens and components never write a literal the user reads.
  Use `stringResource(Res.string.<key>)`, or `pluralStringResource` when the text counts something. Keys are
  grouped by prefix like the web's `en.json`: `nav_`, `common_`, `home_`, `summary_`, `receiving_`, `count_`,
  `status_`, `login_`, `invoice_` / `invoices_`, `catalog_`. The default language is Spanish (`values/`); English is `values-en/`. Both must have the
  same keys; the code does not change.
- **Rejections are translated by case, not shown raw.** The domain's and the API's messages are English and
  technical; the app maps `DomainError` cases and the API's `code` to strings (`feature/receiving/ReceivingMessages.kt`,
  `feature/auth/LoginMessages.kt`, and `core/network/ApiFailureText.kt` for what no screen has a better sentence for).
- **The API is reached only through `ApiClient`.** A call returns an `ApiResult`; a refused or failed call is a value,
  never an exception. What the API sends is mapped to the domain's types, and a value the domain refuses makes the
  answer `Unexpected`. Data a screen loads is held as `Loaded` (value, loading, failure), so a reload keeps the last
  value on screen.
- **Secrets are never printed.** The tokens live in memory, in `Session`, and nowhere else; the classes that carry a
  password or a token are not data classes (or override `toString`), so logging one cannot leak it.
- **Do not call the domain's inline helpers** (`map`, `flatMap`…) from the app: they are compiled for a newer JVM
  than the app's Android target and do not inline. Use `when` on `Either`.
- **The rules live in `stock-domain`.** The app does not reimplement quantity, state or location rules.
  It checks what the operator typed (text, sign) and asks the domain. Where the app needs to know a rule to draw a
  screen (which lines still wait for a count), a test ties the app's answer to the domain's, status by status.
- **A screen does not offer what the domain would refuse.** A line that no longer waits for its count shows what
  was counted and no pad. The domain still guards the rule; the screen just does not invite the error.
- **UI goes through `core/ui` and `core/theme`.** Screens use `ITPage`, `ITCard`, `ITButton`,
  `ITInput`, `ITInputNumber`, `ITNumberPad`, `ITChip`, `ITStatCard` / `ITStat`, `ITAlert`,
  `ITProgress`, `ITList` and the states (`ITLoadingState`, `ITEmptyState`, `ITErrorState`).
  Colors come from `MaterialTheme.colorScheme`, `Brand` and `ITColors` (status only); corners from `ITShape`;
  spacing from `ITSpacing`. No hardcoded colors, radii or sizes in screens.
- **Components carry the `IT` prefix of the web's design system** (`@axzydev/axzy_ui_system`), and the same name
  when the web has the piece: `ITButton`, `ITCard`, `ITChip`, `ITInput`, `ITStatCard`, `ITAlert`, `ITProgress`,
  `ITEmptyState`, `ITLoader`, `ITPage`, `ITDrawer`. Their radii and neutral colors are the library's too
  (`core/theme/Shape.kt`, `core/theme/Color.kt`), so the app and the web read as one product.
- **One component per file** in `core/ui`. A component that needs a text takes it from the caller or from
  `common_*` keys; it never embeds a sentence.
- **Vertical slices.** A feature lives in `feature/<name>` and does not import another feature, and `core` does not
  import a feature. Anything two features need goes to `core/` (that is why the receiving model is in
  `core/receiving`).
- **MVVM per screen.** `<X>UiState.kt` (immutable data class), `<X>ViewModel.kt` (`StateFlow`, no
  navigation, no text), `<X>Screen.kt` (UI only: reads the state, calls the ViewModel, and reports
  navigation through callbacks). A ViewModel lives as long as its screen is on the back stack
  (`core/nav/ScreenViewModels`); it reads from the repository when it is created and never keeps a copy for later.
- **Navigation is explicit.** A screen is a `Screen` value; `App.kt` maps each value to its route and
  `Navigator` holds the stack. The drawer and the tab bar open a section from its start. A screen that works on
  data carries only the id.
- **Code and docs are in English; the interface is in Spanish by default** ([ADR-0018](../docs/adr/0018-todo-en-ingles.md), exception for the app).

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

Tests that touch the API use `core/network/TestApi` (commonTest): the real `ApiClient` over Ktor's mock engine, with
the bodies of `docs/api`. No test needs the API running.

## Running it

The app signs in against the local stack of [ARRANQUE.md](../ARRANQUE.md) (`core/network/ApiBaseUrl`): the iOS
simulator reaches it at `127.0.0.1:8081` and the Android emulator at `10.0.2.2:8081`. A physical device cannot reach
it yet: the address is not a setting. Receiving lists the invoices in `PENDING` and `IN_PROGRESS`; they are
registered on the web.
