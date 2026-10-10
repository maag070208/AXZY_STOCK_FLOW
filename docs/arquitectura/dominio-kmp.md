# Domain — pure KMP (`stock-domain/`)
> **Normative.** Purity rules, contents and why the business rules live here.
> Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** Shared domain in force (plan D6). The paths in the text are from the previous code. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

## 4.1 The rule: pure KMP, no dependencies

**It can only use `kotlin-stdlib` + `kotlinx-datetime`, `kotlinx-serialization` and
`kotlinx-coroutines`.** Nothing else. That is what allows the same code to make the same decisions in the backend, on Android and on
iOS.

**It is enforced by an architecture guard**, not by good intentions:

- `verifyDomainPurity` (`stock-domain/build.gradle.kts`) fails the build if any production
  configuration declares a forbidden framework (`ktor`, `exposed`,
  `sqldelight`, `compose`, `androidx`, `hikari`, `postgresql`, `flyway`, `logback`, `koin`,
  `spring`, `micrometer`, `jackson`, `okhttp`, `retrofit`) or if any file imports one.
  It runs over the **declared** dependencies and over the imports of `commonMain`,
  `jvmMain`, `androidMain` and `iosMain`.
- `forbidJvmMainSourceSet` fails if code appears in `src/jvmMain`, `src/androidMain` or
  `src/iosMain`: the domain must be common, and `jvmMain` is the door through which Exposed would enter.

Both hang off `check`, so they cannot be skipped by mistake, and they leave a record:

```
✓ stock-domain is pure Kotlin (architecture guard OK)
✓ stock-domain has no platform-specific code
```

**It also checks that a JVM-only annotation is not used unqualified.** `@JvmInline`
compiles on JVM and **breaks the iOS build** with "Unresolved reference 'JvmInline'"; the correct and
multiplatform form is `@kotlin.jvm.JvmInline`. The guard detects the same thing
that Kotlin/Native found, without needing Xcode installed.

**`androidMain` has no dependencies, and that is deliberate.** The scanner contract
(`ScanWindow`, `ScanState`, `ScanOutcome`) and the model of what to do with what was scanned live
in the domain; the implementation with CameraX and ML Kit goes in a mobile module. Adding the
camera dependencies here would be a misleading dependency: it would look as if the domain knew about the camera, and it does not.

## 4.2 What lives here

| Folder | Contents |
|---|---|
| `value/` | `Id`, `Quantity`, `ProductCode`, `LocationCode`, `BoxQuantity`, `UnitsPerBox` — types that **cannot be built wrongly** |
| `common/` | `Either`, `DomainResult` |
| `error/` | `DomainError` — the catalog of business errors |
| `inventory/` | `StockMovement`, `InventoryLedger`, `Adjustment`, `LocationContent` |
| `receiving/` | `InvoiceLine`, the state machine |
| `access/` | `PermissionCatalog`, `AccessEngine`, `AccessModel`, `RoleDefinitions`, `StockPolicies` — RBAC + ABAC |
| `audit/` | `AuditModel`, `AccessDecisionTrace`, `AuditableUseCase` |
| `catalog/` | `CatalogImporter` — the catalog import model |
| `fulfillment/` | `Order`, the picking state machine |
| `identity/` | `PasswordPolicy`, `SessionModel` |
| `labeling/` | `LabelTemplate` |
| `scanner/` | `ScanModel` — the scanner contract |
| `transfer/` | `Transfer`, the transfer state machine |

## 4.3 Why the rules live in the domain

**A rule in the domain can be tested in milliseconds and holds in all three parts.**
A rule in an endpoint is tested over HTTP and forgotten in the next one.

Real example: `AdjustmentRequest.create`
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/Adjustment.kt`) requires a
**positive** quantity: it rejects `quantity.isZero || quantity.isNegative`. If it only checked
`isZero`, a **negative** quantity with direction `DECREASE` would **add inventory
without needing approval**. The check lives in the type's factory and not in the endpoint,
because **the next route that is added would have the same hole**.

**Corollary:** if you can build an invalid object, the type is badly designed. Private
constructors with factories that return `Either` are used.

The access rule —RBAC + ABAC and default denial— lives in `access/` and is documented in
[roles-permisos.md](../seguridad/roles-permisos.md); the decision, in
[ADR 0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md).
