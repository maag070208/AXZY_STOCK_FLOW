# Backups
> **Plan.** Status: **there is no backup mechanism in the repo.** Last verified: 2026-10-09.

## What was checked

Search for `backup`, `respaldo` and `pg_dump` in `infra/`, `scripts/` and `.github/`: no results.
There is no tested restore procedure either.

## Why it matters

The cutover to the new system relies on a verified backup ([migracion/cutover.md](../migracion/cutover.md)).
Without it, the reversal described in that document is not possible.

## Minimum requirements before the cutover

1. Complete dump of the PostgreSQL database, with its date and its schema version.
2. Tested restore in a separate database, with the check of `comprobar_proyecciones()`.
3. Who runs it and where it is stored, decided outside the repo.

The concrete commands are written when the mechanism exists; they are not documented here before being tested.
