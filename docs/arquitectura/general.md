# General architecture: API, app and web
> **Normative.** How the three parts of StockFlow relate to each other and what they share. Last revised: 2026-10-09.

| Part | Technology | Architecture | Unit of organization | Strength |
|---|---|---|---|---|
| **API** | Kotlin + Spring Boot, PostgreSQL | Layers with dependencies pointing inward | Layer, and within it the business concept | The business rule depends on nothing |
| **App** | Kotlin Multiplatform (Android, iOS) | Vertical slice with a shared core | The floor functionality | Changing one screen touches a single folder |
| **Web** | React + Vite + AXZY UI System | Feature-Sliced Design | The layer, and within it the feature | One page does not depend on another |
| **Shared domain** (if D6) | Kotlin Multiplatform without a framework | Pure rules | The business concept | Does not depend on anyone |

Each part uses the architecture that solves its dominant problem:

- **API.** Inventory rules must hold no matter who writes. That is why the API has
  layers with enforcement and the engine's rules live in the database ([api-spring.md](api-spring.md),
  [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md)).
- **App.** The floor operator needs a new screen not to break another. That is why each
  functionality is a complete folder ([app-kmp.md](app-kmp.md), [ADR-0003](../adr/0003-vertical-slice-en-la-app.md)).
- **Web.** Administration has complex state and many screens. That is why the dependencies between
  layers are enforced with import rules ([web-fsd.md](web-fsd.md), [ADR-0002](../adr/0002-fsd-en-la-web.md)).

## Rules that hold for all three parts

1. **English names** in code, tables, columns and endpoints. Only comments, error messages, visible names and
   error codes, which are part of the contract, are written in Spanish ([ADR-0004](../adr/0004-nombres-en-ingles-comentarios-en-espanol.md)).
2. **Each business rule lives in a single place.** The flows in [flujos/](../flujos/README.md) are
   the reference; the code and the tests implement them. No part reimplements a rule
   that is already in the API or in the shared domain.
3. **The API is the source of truth.** The app and the web do not keep their own inventory: they show what the
   API answers, and the app only keeps a cache to work without network if an ADR decides so.
4. **Business errors are stable codes** ([api/errores.md](../api/errores.md)). The visible text
   changes; the code does not.
5. **Every write is recorded in the audit log** ([flujos/auditoria.md](../flujos/auditoria.md)).

## Graph between parts

```
                  shared domain  (pure KMP, if D6)
                    │              │
                    ↓              ↓
   web (React) ──HTTP──→ API (Spring) ──→ PostgreSQL
                    ↑              ↑
                    └── app (KMP)──┘
                     (HTTP, same contract)
```

- The web and the app talk to the API through the same HTTP contract ([api/convenciones.md](../api/convenciones.md)).
- The API and the app share the domain only if D6 is approved. If not, the API has its own copy of
  the rules and the reference document is the one in [flujos/](../flujos/README.md).

## What is outside this document

- Detail of each layer: [api-spring.md](api-spring.md), [app-kmp.md](app-kmp.md), [web-fsd.md](web-fsd.md).
- Versions: [stack.md](stack.md).
- Environment and migrations: [infraestructura.md](infraestructura.md).
- Plan and phases: [plan/PLAN-REHACER.md](../plan/PLAN-REHACER.md).
