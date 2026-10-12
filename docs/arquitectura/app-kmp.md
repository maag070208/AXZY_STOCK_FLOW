# App: Kotlin Multiplatform for Android and iOS
> **Normative.** How the floor app is organized. Last revised: 2026-10-11.

## 1. What the app does

The app is for the **floor operator**: receives goods, locates them, picks orders, moves stock and
scans labels. It does not manage users, catalog or reports; those belong to the web
([plan/PLAN-REHACER.md](../plan/PLAN-REHACER.md), §2).

Each screen corresponds to a flow in [flujos/](../flujos/README.md). The app has no rules of its own:
it shows what the API answers and sends what the operator does.

## 2. Structure

A Gradle project with a `shared` module (KMP) and the Android and iOS targets.

```
mobile-app/          Its own build: `settings.gradle.kts`, `gradlew` (ADR-0003)
  shared/
    commonMain/
      composeResources/  Every text the user reads (`values/strings.xml`), the brand glyph, the Lottie loader.
      core/
        theme/         Colors, shapes, spacing and the Material theme.
        nav/           The screens, the back stack, the drawer and the tab bar.
        ui/            The reusable components, one file each, named as in the web's design system (`IT*`).
        network/       The one client of the API, its failures, and where the API answers.
        session/       Who is signed in: sign-in, renewal and sign-out.
        receiving/     What home, receiving and the catalog share: the invoices and lines as the API has them
                       (the repository), the summary, and the rows.
      feature/
        auth/          The sign-in screen. Password change is not built yet.
        home/          Who is signed in, how the open receiving stands, the next invoices, and the modules to open.
        catalog/       The component catalog, for design review.
        receiving/     The open invoices, the lines of one, and the count of a line. Locating is not built yet.
        fulfillment/   Orders, packing (phase 7).
        transfers/     Transfers (phase 8).
        labels/        Label scanning and lookup (phase 9).
        inventory/     Stock lookup (phase 5).
    androidMain/       Android: the system back button, camera and permissions
    iosMain/           iOS: the host controller, camera and permissions
  androidApp/
  iosApp/              Xcode project: a SwiftUI host around `MainViewController`
```

**Locality rule (vertical slice).** A feature has its screens, its state, its calls and its models
inside its folder `feature/<name>`. A feature **does not import** from
another; if two need the same thing, it goes to `core/`.

**Text rule.** Screens and components never write a literal the user reads. Every text comes from
`composeResources/values/strings.xml` through `stringResource` or `pluralStringResource`. The default is Spanish; English is `values-en/`. Keys are grouped by prefix, as the web's locale files are.

**Design rule.** The app and the web are one product. The app's components carry the `IT` prefix of the web's
design system (`@axzydev/axzy_ui_system`) and the same name when the web has the piece (`ITButton`, `ITCard`,
`ITChip`, `ITInput`, `ITStatCard`, `ITAlert`, `ITProgress`, `ITEmptyState`, `ITLoader`, `ITPage`, `ITDrawer`).
Its radii and neutral colors are the library's (`radius-xl` for cards, `radius-lg` for controls, the slate scale
for borders), and its brand colors are the web theme's. A screen takes all of them from `core/theme` and `core/ui`.

**Navigation.** Nothing behind the sign-in is composed while nobody is signed in. Once somebody is, the top-level
screens (home, receiving, the component catalog) share a tab bar and a drawer; either one opens a section from its
start, and the drawer also signs out. A screen opened from another (the lines of an invoice, the count of a line)
shows a back arrow instead.

**State follows the back stack.** Each screen on the stack has its own ViewModels (`core/nav/ScreenViewModels`): a
screen keeps its state while it can still be gone back to, and loses it when it leaves the stack, so a screen opened
again reads what the repository has now. Everything loaded for a session ends with the session.

**Receiving.** The section lists the invoices that still take counts (`PENDING` and `IN_PROGRESS`), an invoice opens
its lines, and a line opens its count. The figures at the top of the lines are also its filters. How many lines of an
invoice are resolved is counted with the domain's rule (`InvoiceLineStatus.blocksInvoiceClosing`), the same the API
uses for `pendingApprovalLines`.

**Counting.** A count is typed on the app's own number pad, not on the system keyboard: the keys are large for a
gloved hand and the pad is always on screen. The domain is asked first, with no network: a count it refuses is never
sent. An accepted one goes to the API, and the line shown afterwards is the one the API answered with. A line that
no longer waits for its count shows what was counted, how it compares with the invoice, and no pad, so the screen
never invites an action the domain would refuse.

## 3. Shared domain

If D6 is approved, the rules the app needs (quantity validation, state transitions, location
code format) come from the `stock-domain` module; they are not rewritten in the app. If D6
is rejected, the app keeps only the form validations and leaves the rules to the API.

## 4. Network and session

- **One client.** Every call goes through `core/network/ApiClient` (Ktor Client; see [stack.md](stack.md)). It adds
  the token and `X-Client-Channel: MOBILE`, and returns an `ApiResult`: a refused or failed call is a value
  (`Rejected` with the API's `code` and `details`, `Unreachable`, `Unexpected`), never an exception.
- **The app decides by `code`.** The API's messages are English and are rewritten; each screen maps the codes it
  knows to its own sentences in `strings.xml` and falls back to a generic one ([api/errores.md](../api/errores.md)).
- **What the API sends becomes the domain's types.** A line arrives as the domain's `InvoiceLine`; a value the domain
  refuses (an unknown status, fractional units per box) makes the whole answer `Unexpected` instead of being shown
  rounded.
- **Renewal.** A `401 UNAUTHENTICATED` trades the refresh token for new tokens and sends the call again, once. Calls
  refused at the same time renew **once**: the API rotates the refresh token and closes every session of the family
  when an old one is used twice ([flujos/autenticacion-y-permisos.md](../flujos/autenticacion-y-permisos.md) §3). A
  renewal the API refuses closes the session; one that got no answer does not.
- **An account that must change its password is not signed in.** The API refuses every other route until it does, and
  the app has no screen for the change yet: it says so and the change is made on the web.

**Not built yet:**

- **The session does not survive closing the app.** The tokens live in memory only. Keeping them needs the system's
  secure storage (Keychain on iOS, Keystore on Android); plain preferences are not an option for a token.
- **The address of the API is fixed per platform** to the local stack of `ARRANQUE.md` (`core/network/ApiBaseUrl`):
  the host machine as the Android emulator and the iOS simulator reach it. A deployed address needs a setting.
  Android allows plain HTTP in debug builds only.
- **`Idempotency-Key`.** The writes the app has do not need it yet: the capture route does not read the header and
  claims the line instead ([api/convenciones.md](../api/convenciones.md) §1.5). Locating and moving will need it.

## 5. Scanner

- Camera with each platform's native API (CameraX on Android, AVFoundation on iOS), behind a
  common interface in `core/`.
- The scanner's coordinate conversion is tested on a real device. A test in the simulator does not
  count as verification of the scanner.

## 6. Offline cache

**Out of scope for the first delivery.** If working without network is needed, it is decided in its own
ADR. Until then, if there is no network, the app shows the status and does not queue writes.

## 7. Tests

| Level | What it tests | Where |
|---|---|---|
| Unit | Forms, states, navigation | `commonTest` |
| Network | The client, the session and the repositories, through the real client over Ktor's mock engine (`core/network/TestApi`): headers, bodies, decoding, error codes, renewal | `commonTest` |
| Device | Scanner, camera, full receiving flow | Manual, documented in [guia/lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md) |

## 8. What the app does not do

- It does not calculate stock or balances.
- It does not store permission rules: it hides options according to what the API reports, but the API decides.
- It does not communicate with the ERP or with printers directly.
