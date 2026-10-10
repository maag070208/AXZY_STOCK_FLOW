# ADR-0001 — Clean Architecture per feature in the API
> **Partial replacement.** The framework of this decision (Ktor) is replaced by [ADR-0014](0014-api-spring-boot-clean-architecture.md). The dependency rule is kept.
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — (reverts the previous vertical slice decision in the API, which had no ADR) |
| **Replaced by** | — |

## Context

The API was organized by feature with everything it needed inside: routes, service, DTOs,
repository and tables in the same folder, with no horizontal layers. The justification was
locality: changing a feature touched a single folder ([api-ktor.md](../historico/API-KTOR.md) §1.1).

Without the dependency rule written down, each file decides for itself: a route can import the
JDBC driver and a DTO can import Ktor. The design degrades without anyone making the decision.
Business logic was also trapped in the routes and, without ports, the rule cannot be tested
without PostgreSQL: the only possible test runs against the real database
([api-ktor.md](../historico/API-KTOR.md) §1.1).

The reversal applies only to the API. The mobile app stays on vertical slice (ADR-0003) and the web
stays on FSD (ADR-0002). It is not the same architecture on purpose: each part chooses it for a
different reason ([general.md](../arquitectura/general.md)).

## Decision

The API uses Clean Architecture (hexagonal) organized by feature, and within the feature,
by layer.

- The dependency rule is `infrastructure → application → domain`. Never the reverse and
  never a skip ([api-ktor.md](../historico/API-KTOR.md) §1.3).
- `domain/` uses the Kotlin stdlib and `stock-domain`. `application/` does not import Ktor,
  Exposed, JDBC, HikariCP or `kotlinx.serialization` in the use case or in the ports.
  The feature's DTOs may use `kotlinx.serialization`; the use case file
  never carries `@Serializable` ([api-ktor.md](../historico/API-KTOR.md) §1.3).
- The layers live inside the feature, never in global folders such as `services/`,
  `repositories/` or `routes/` ([api-ktor.md](../historico/API-KTOR.md) §1.2, §1.4).
- The transactional scope is defined by the use case, not by the route ([api-ktor.md](../historico/API-KTOR.md) §1.12).
- Dependencies are injected through the constructor, never with an `object` ([api-ktor.md](../historico/API-KTOR.md) §1.8, `backend/src/main/kotlin/dev/axzy/stock/backend/config/AppDependencies.kt`).
- The migration is done per feature and compiling between phases. `catalog/` is already split
  by resource (`backend/src/main/kotlin/dev/axzy/stock/backend/catalog/`).

## Consequences

An explicit dependency direction is gained, a business rule that is tested without a database,
and a use case that can be exposed through another channel without dragging the HTTP representation along.

More structure per feature is paid for: more files, more imports to maintain and a migration
that has not finished yet. The target tree of [api-ktor.md](../historico/API-KTOR.md) §1.2
**already exists for `inventory/`, `receiving/` and `transfer/`**, and `catalog/` is split
by resource with its services; the rest of the features still follow `package-by-feature`
([migracion-capas.md](../historico/MIGRACION-CAPAS-KTOR.md) and
[lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md)).

Operational rules that follow:

- The backend cannot have global layer folders. What two or more features use goes to
  `config/`, `error/`, `security/` or `application/shared/`, not to a drawer per file
  type ([api-ktor.md](../historico/API-KTOR.md) §1.4).
- A use case file is not annotated with `@Serializable`.
- The rule has no automatic guard yet: it is checked by hand with the `grep` of
  [api-ktor.md](../historico/API-KTOR.md) §1.3.

## Alternatives considered

- **Keep vertical slice in the API.** Locality is real, but there is no written dependency
  direction and the business rule ends up spread across the handlers
  ([api-ktor.md](../historico/API-KTOR.md) §1.1).
- **Go back to global folders per layer.** The service in one folder and all the HTTP routes in
  another forces jumping between two places to touch one feature, and `routes/` ends up being a
  drawer of files with no relation to each other
  ([api-ktor.md](../historico/API-KTOR.md) §1.2).
- **Migrate everything at once with a script.** It was tried and reverted on purpose: the
  refactor broke imports en masse and the only thing that could be verified was the wreckage. It is done
  per feature and compiling between phases ([api-ktor.md](../historico/API-KTOR.md) §1.2).

## References

- [general.md](../arquitectura/general.md) and [api-ktor.md](../historico/API-KTOR.md) §1.1, §1.2, §1.3, §1.4, §1.8, §1.12, §1.18
- `backend/src/main/kotlin/dev/axzy/stock/backend/catalog/product/ProductRoutes.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/catalog/product/ProductRepository.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/config/AppDependencies.kt`
- `backend/build.gradle.kts`
- `stock-domain/build.gradle.kts`
