# ADR-0018 — Everything in English: code, interface, data, messages and documentation
> **Normative.** Accepted decision. Last verified: 2026-10-10.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-10 |
| **Replaces** | [ADR-0004](0004-nombres-en-ingles-comentarios-en-espanol.md) (comments and documentation in Spanish) and [ADR-0017](0017-identificadores-y-datos-en-ingles.md) (pending exceptions) |
| **Replaced by** | — |

## Context

ADR-0004 left the comments, the documentation and the error messages that the operator reads in Spanish. ADR-0017 moved the
codes, keys and data to English. Still in Spanish were the interface, the API messages, the test names, the SQL engine
functions and the comments. The project owner asked that everything that is Spanish move to English, with i18n for
the interface.

## Decision

Everything the project writes goes in English:

- **Code**: identifiers, comments, test names and file names.
- **Web interface**: all visible text comes from i18next (`src/shared/i18n/locales/en.json`). The API
  errors are translated by code; the server message is never shown.
- **Data and contract**: role keys, warehouse and location codes (ADR-0017), names of permissions
  and visible roles.
- **API messages**: in English. What the operator reads in a log also goes in English.
- **SQL functions**: they are renamed with a new migration; the bodies that call each other are rewritten in the same migration.
- **Documentation** (`docs/`, `ARRANQUE.md`, `README.md`): in English.

**Exceptions that stay**, because they are data and not text: the unit of measure `PZA` of the legacy catalog
and the letter `Ñ` of the product code alphabet.

## Migration status

- Verified: complete backend (`./gradlew verify`) and browser suite (30/30).
- Done: comments in the code (Kotlin and web), verified with the domain tests and the catalog flow test.
- In progress: documentation (`docs/`, `ARRANQUE.md`, `README.md`).
- Pending: Kotlin test names, SQL functions (migration `V17`).

## Consequences

- A documentation set in English changes every link and every table in `docs/`; it is done in one pass
  with link verification, not file by file.
