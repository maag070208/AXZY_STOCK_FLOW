# ADR-0002 — FSD in the web
> **In force.** The web is built with React and AXZY UI System; see [arquitectura/web-react.md](../arquitectura/web-react.md) for the kit's conventions.
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The web is React 19 + Vite + TypeScript. A stateful interface has many modules that import
each other, and without a dependency direction rule two screens end up coupled: changing one breaks
the other and nobody sees it until deployment ([web-fsd.md](../arquitectura/web-fsd.md) §2.2).

FSD (Feature-Sliced Design) was chosen because its strength is exactly that: the dependency
direction. Unlike the API, here the unit of organization is the layer, and within the layer
the feature ([general.md](../arquitectura/general.md)).

## Decision

`web/src/` is organized into FSD layers: `app/`, `pages/`, `widgets/`, `features/`, `entities/`
and `shared/` ([web-fsd.md](../arquitectura/web-fsd.md) §2.1).

- A layer can only import from the layers below it:
  `app → pages → widgets → features → entities → shared` ([web-fsd.md](../arquitectura/web-fsd.md) §2.2).
- It is forbidden for a page to import from another page, for a feature to import from another
  feature, and for `shared/` to import from any layer. If `shared/` imports from `entities/`, the
  architecture is broken ([web-fsd.md](../arquitectura/web-fsd.md) §2.2).
- The rule is enforced by the lint, not by convention: `eslint-plugin-boundaries` with
  `default: "disallow"` and an explicit policy per layer (`web/eslint.config.js`).
- Server data is handled according to the contract: the routes go without `/v1` because
  `API_CONSTANTS.BASE_URL` already includes it (`web/src/shared/api/constants/API_CONSTANTS.ts`),
  the visible name of a location is composed by the server, and decimals arrive as
  `string`, not as `number` ([web-fsd.md](../arquitectura/web-fsd.md) §2.3).
- A screen without an endpoint does not show invented rows: it says what it will do and that the endpoint
  does not exist ([web-fsd.md](../arquitectura/web-fsd.md) §2.3).

## Consequences

Changing one screen does not break another, and the dependency direction is checked on every
lint run instead of depending on the reviewer's memory.

Ceremony is paid when moving code: if a widget needs an action from a feature, either it is
passed through props or the code moves down a layer. `shared/` cannot contain business logic.

Operational rules that follow:

- When a layer needs to import from a higher one, the design is wrong. The fix is to move the
  code down a layer, not to silence the lint rule.
- `shared/` holds `api`, `ui`, `lib` and `config`, without business rules
  ([web-fsd.md](../arquitectura/web-fsd.md) §2.1).

## Alternatives considered

- **Organize by file type** (`components/`, `hooks/`, `services/`). It is the drawer by type that FSD
  exists to avoid: there is no way to know what can import what.
- **Leave the dependency direction unchecked.** It is what existed before the rule: the coupling
  comes back with the first shortcut.
- **Organize by feature without layers.** The distinction between entity, feature, widget
  and page is lost, and a page can end up importing from another page.

## References

- `web/eslint.config.js`
- `web/src/app/`, `web/src/pages/`, `web/src/widgets/`, `web/src/features/`,
  `web/src/entities/`, `web/src/shared/`
- `web/src/shared/api/constants/API_CONSTANTS.ts`
- [general.md](../arquitectura/general.md) and [web-fsd.md](../arquitectura/web-fsd.md) §2.1, §2.2, §2.3
