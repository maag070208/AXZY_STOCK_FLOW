# Respaldos

> **Plan.** Estado: **no existe mecanismo de respaldo en el repo.** Última verificación: 2026-10-09.

## Qué se comprobó

Búsqueda de `backup`, `respaldo` y `pg_dump` en `infra/`, `scripts/` y `.github/`: sin resultados.
Tampoco hay un procedimiento de restauración probado.

## Por qué importa

El corte al sistema nuevo se apoya en un respaldo verificado ([migracion/cutover.md](../migracion/cutover.md)).
Sin él, la reversión descrita en ese documento no es posible.

## Requisitos mínimos antes del corte

1. Volcado completo de la base de PostgreSQL, con su fecha y su versión de esquema.
2. Restauración probada en una base separada, con la comprobación de `comprobar_proyecciones()`.
3. Quién lo ejecuta y dónde se guarda, decididos fuera del repo.

Los comandos concretos se escriben cuando exista el mecanismo; no se documentan aquí antes de probarlos.
