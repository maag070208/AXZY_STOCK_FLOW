# ADR-0014 — The API in Kotlin with Spring Boot, layered, with constructor injection
> **Normative.** Accepted decision. It replaces the framework part of [ADR-0001](0001-clean-architecture-en-la-api.md).

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | [ADR-0001](0001-clean-architecture-en-la-api.md) (the framework only; the dependency rule still applies) |
| **Replaced by** | — |

## Context

The previous API was Ktor with hand-wired dependencies (`by lazy`) and Koin on the classpath without being used.
Each dependency was resolved with custom code, and that left two problems: the lifecycle of the
objects was not visible in a single place, and the dependency rule was verified by convention, not by the
compiler. The team knows Spring Boot and needs a framework with mature dependency injection,
broad documentation and frictionless Docker support.

## Decision

1. **Kotlin with Spring Boot** for the API, on a JDK LTS (the exact version is pinned in phase 0,
   [stack.md](../arquitectura/stack.md)).
2. **Layers in separate Gradle modules**, and the dependency can only point inward:

   ```
                    ┌──→ adapters-web ─────────┐
   app ─────────────┼──→ adapters-persistence ─┼──→ application ──→ stock-domain
                    └──→ adapters-security ────┘
   ```

   - `stock-domain`: entities, states, typed errors and rules. **It does not depend on Spring or JDBC.**
     It is the domain shared with the app (plan D6); the API has no domain module of its own.
   - `application`: use cases and **ports** (interfaces). It depends only on `stock-domain`.
   - `adapters-persistence`: implements the outbound ports with PostgreSQL.
   - `adapters-security`: implements the cryptography ports (tokens, password hashing).
   - `adapters-web`: HTTP controllers that call the use cases.
   - `app`: the only module that **wires** everything. It is the only one that depends on all of them.

   The three adapters do not know each other. `adapters-security` was split off from the other two while
   implementing: cryptography is neither persistence nor HTTP, and isolating it allows testing it on its own.

3. **Dependency injection through the constructor.** The use cases are plain Kotlin classes with
   their ports in the constructor. Spring is only used to instantiate them in `app`, in a
   configuration per module. A use case can be tested without starting Spring.
4. **Transactions are a port.** `application` defines `TransactionRunner`; the Spring adapter
   (`TransactionTemplate`) implements it in `app`. That way the application does not depend on
   `@Transactional`.
5. **Authorization is decided in `application`**, not only in the controller. A new endpoint that
   forgets the controller cannot skip the permission rule.

## Consequences

- The dependency rule is checked by the compiler (the modules cannot see each other in the forbidden
   direction) and an ArchUnit test repeats it on every build.
- The full wiring is in a single place (`app`); it is the first thing read when looking for
  where an object is created.
- More Gradle modules than a single-module project: the cost is paid in initial configuration.
- Spring stays outside the domain: if the framework changes, `app` and the adapters change, not
  the rules.

## Discarded alternatives

- **A single module with packages and no enforcement.** The layers degrade over time without
  anyone noticing; it is the problem the previous architecture already had.
- **Spring annotations in the domain** (`@Service`, `@Transactional`). It couples the rules to the framework and
  makes the domain tests need a Spring context.
- **Koin instead of Spring.** It was already on the classpath of the previous API without being used; the team
  has no experience with it and Spring offers the same with more documentation.
