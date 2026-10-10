# ADR-0005 — An expected error is returned, not thrown
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The legacy returned HTTP 200 on business errors and 500 with an `{}` body
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`). A
business result modeled as an exception leads to `try/catch` everywhere and, with it, to
silenced errors: "the quantity exceeds the invoice" is not a program failure,
it is an expected result (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/common/Either.kt`).

`kotlin.Result` does not work for that case because it forces the error to be a `Throwable`, and an
expected business result is not one (`Either.kt`). A custom result type was needed
([api-ktor.md](../historico/API-KTOR.md) §1.11).

## Decision

- An expected business error is returned. The operation returns `Either<DomainError, A>`,
  with the alias `DomainResult<A>` for the domain case (`Either.kt`).
- `DomainError` is a closed `sealed` catalog, with a `code` that is stable for the client and a
  readable `message`. A new error is added to the catalog before it is used (`DomainError.kt`,
  [errores.md](../api/errores.md)).
- The signature declares the failures: `suspend fun execute(...): Either<DomainError, ...>`. A
  function that returns `Unit` hides in its type what can fail ([api-ktor.md](../historico/API-KTOR.md) §1.11).
- The `try/catch` is encapsulated at the boundary —the repository or the external client— and there it is
  translated into a typed failure. A `SQLException` does not go up into the application
  ([api-ktor.md](../historico/API-KTOR.md) §1.11).
- A programming failure —impossible state, a `when` that is not exhaustive— is thrown: it is a bug and
  it has to be visible ([api-ktor.md](../historico/API-KTOR.md) §1.11).

## Consequences

An exhaustive `when` over `DomainError` does not compile if a new unhandled case appears, so
the handling cannot be forgotten (`DomainError.kt`). Each error has a stable code that
maps to HTTP without losing information, and the client decides with the `code`, not with the message
(`DomainError.kt`, `backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`).

The price is that the type spreads: a use case that returns `Either` forces its callers
to handle it, and the `try/catch` at the boundary cannot be forgotten.

Actual status, which should not be disguised: the rule is met in the domain and only partly in
the API. The routes and services still throw HTTP exceptions —for example
`backend/src/main/kotlin/dev/axzy/stock/backend/order/OrderService.kt`—, and the backend's own storage failure
type (`DomainFailure`) does not exist yet, so a database failure has no typed place to live
([api-ktor.md](../historico/API-KTOR.md) §1.11, §1.18 and [lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md)). While
returning and throwing coexist, the signature of a use case does not tell the whole truth.

Operational rules that follow:

- An exception is not thrown for a business error.
- A business error is not answered with `500`: a `500` says the server failed when the
  server is fine, and a client that retries on a `500` retries forever
  ([errores.md](../api/errores.md)).
- Error codes come from the closed catalog and are not invented ([errores.md](../api/errores.md)).

## Alternatives considered

- **Exceptions with a global handler (`StatusPages`).** The route does not declare what it can
  fail with, and the handling falls outside the view of whoever writes it ([api-ktor.md](../historico/API-KTOR.md) §1.11).
- **`kotlin.Result`.** It forces the error to be a `Throwable` (`Either.kt`).
- **`arrow-kt`.** It adds a dependency to a domain that must be pure Kotlin
  (`Either.kt`, `stock-domain/build.gradle.kts`).

## References

- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/common/Either.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/Adjustment.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/order/OrderService.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`
- [api-ktor.md](../historico/API-KTOR.md) §1.11, §1.18 and [errores.md](../api/errores.md)
