# ADR-0003 — Vertical slice in the mobile app
> **In force.** Kept for the KMP app; see [arquitectura/app-kmp.md](../arquitectura/app-kmp.md).
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The app is compiled as its own Gradle build, separate from the monorepo, because a directory cannot be the root of two
builds, and including it with `include(":mobile-app")` leaves the project without a root and breaks the IDE sync
(`mobile-app/settings.gradle.kts`, `settings.gradle.kts`, [app-kmp.md](../arquitectura/app-kmp.md) §3.1).

The app consumes the domain as an included build (`includeBuild("..")`), which is the same
instance and not a copy: the business decisions are identical in backend and mobile by construction
(`mobile-app/settings.gradle.kts`).

Its architecture is vertical slice: the feature is the unit of organization and there is a shared
`core/`. Its strength is locality —changing a feature touches a single folder— and it is, on purpose, the
architecture that the API abandoned in ADR-0001 ([general.md](../arquitectura/general.md) and [app-kmp.md](../arquitectura/app-kmp.md) §3.1).

## Decision

- `mobile-app/` is a self-contained KMP build with two modules, `shared/` and `androidApp/`, and
  its own `settings.gradle.kts` (`mobile-app/settings.gradle.kts`).
- The domain is not duplicated: it is consumed through `includeBuild("..")` with substitution by
  coordinate (`mobile-app/settings.gradle.kts`).
- Inside `shared`, the code is split into `core/` (shared: `theme`, `ui`, `camera`,
  `nav`, `data`) and `feature/` (one folder per feature: `auth`, `home`, `inventory`,
  `invoices`, `labels`, `locations`, `orders`).
- A feature change touches a single folder of `feature/`. What two features use is promoted to
  `core/`; it is not copied.
- The JVM and Android targets are always present. iOS is opt-in with `-PenableIosTargets=true`
  ([app-kmp.md](../arquitectura/app-kmp.md) §3.2).
- The mobile commands are run from `mobile-app/`, not from the root
  ([app-kmp.md](../arquitectura/app-kmp.md) §3.3).

## Consequences

Locality is gained, and a test that runs on the JVM in seconds, without an emulator: it is what validates the
common code of the app ([app-kmp.md](../arquitectura/app-kmp.md) §3.2).

The price is that the commands cannot be run from the root, that iOS requires remembering the flag and that the
`.xcodeproj` is not verified in Xcode ([app-kmp.md](../arquitectura/app-kmp.md) §3.2 and [lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md)).

Operational rules that follow:

- The business rules live in `stock-domain`, not in `feature/`. If a rule is written twice, one of the two
  will stop being true.
- The domain is not copied into the app: it is consumed as an included build.
- The vertical slice has no automatic guard. Discipline belongs to the reviewer: a feature does not
  import from another feature, and what is shared is declared in `core/`.

## Alternatives considered

- **Include `mobile-app` as a submodule of the monorepo.** Checked and discarded: the
  directory stops being the root of its build, IntelliJ does not sync and
  `./gradlew :mobile-app:shared:jvmTest` fails (`settings.gradle.kts`).
- **FSD in the app.** The layers inside each feature complicate the Compose setup and do not
  provide the rule that matters in the web, which is the direction between screens
  ([general.md](../arquitectura/general.md)).
- **Copy `stock-domain` inside the app.** It would mean two definitions of the same business rule,
  which is exactly what the shared module avoids (`mobile-app/settings.gradle.kts`).

## References

- `mobile-app/settings.gradle.kts`
- `settings.gradle.kts`
- `mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/App.kt`
- `mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/core/`
- `mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/feature/`
- `stock-domain/build.gradle.kts`
- [general.md](../arquitectura/general.md) and [app-kmp.md](../arquitectura/app-kmp.md) §3.1, §3.2, §3.3
