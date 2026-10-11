# App: Kotlin Multiplatform for Android and iOS
> **Normative.** How the floor app is organized. Last revised: 2026-10-09.

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
        nav/           The screens and the back stack.
        ui/            The reusable components, one file each (cards, chips, buttons, lists, states).
        network/       Network, session, errors, settings (phase 3).
      feature/
        home/          The modules an operator can open.
        catalog/       The component catalog, for design review.
        auth/          Sign-in, session, password change (phase 3).
        receiving/     Invoices, lines, capture, locate.
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
`composeResources/values/strings.xml` through `stringResource`, grouped by prefix as the web's `en.json`.

## 3. Shared domain

If D6 is approved, the rules the app needs (quantity validation, state transitions, location
code format) come from the `stock-domain` module; they are not rewritten in the app. If D6
is rejected, the app keeps only the form validations and leaves the rules to the API.

## 4. Network and session

- HTTP client with Ktor Client (client only; see [stack.md](stack.md)).
- The access token lives in the system's secure storage (Keychain on iOS, Keystore on Android),
  never in plain text.
- A `401` triggers a refresh; if the refresh fails, the session is closed. A reused refresh
  invalidates the whole session ([flujos/autenticacion-y-permisos.md](../flujos/autenticacion-y-permisos.md) §3).
- All writes that may be retried carry an `Idempotency-Key` generated in the app and kept
  until the API confirms.

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
| Unit | Forms, states, response mapping | `commonTest` |
| Network | Contract against the API in Compose | `commonTest` with a mock server generated from the contract |
| Device | Scanner, camera, full receiving flow | Manual, documented in [guia/lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md) |

## 8. What the app does not do

- It does not calculate stock or balances.
- It does not store permission rules: it hides options according to what the API reports, but the API decides.
- It does not communicate with the ERP or with printers directly.
