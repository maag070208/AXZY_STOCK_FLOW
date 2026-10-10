# ADR-0012 — Reconciliar el stock heredado con conteo físico antes del corte
> **Normativo.** Decisión aceptada; ejecución pendiente. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada. Ejecución pendiente |
| **Fecha** | 2026-10-09 (decisión R-73 y R-81 del plan original) |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El Excel de inventario trae un total de 10.914.130 unidades para 1.398 productos, pero no
trae el desglose por ubicación. El total se considera fiable; el desglose no existe en la
fuente. Cargarlo a una sola ubicación inventaría mal desde el primer día.

## Decisión

- **Antes de cortar**, se hace un conteo físico de los 1.398 productos y se reconcilia contra
  el total heredado. Es un proyecto aparte, con su propia pantalla de conteo.
- El stock inicial entra a `SIN-UBICAR` ([ADR-0009](0009-ubicacion-de-recepcion-real.md)) y
  sale de esa ubicación al ubicarse con conteo, no con un ajuste sin firma.
- El corte no se hace hasta que la reconciliación cuadre o las diferencias tengan un
  ajuste con motivo ([ADR-0008](0008-discrepancias-y-merma.md)).

## Consecuencias

- El corte depende de una tarea operativa fuera del código. Sin ella, la migración no cierra.
- El plan de corte y su reversión están en [migracion/cutover.md](../migracion/cutover.md).

## Alternativas descartadas

- **Confiar en el total del Excel sin contar.** Es lo que deja el desglose inventado.
- **Ajustar en bloque al importar.** Un ajuste masivo sin conteo no tiene motivo verificable.
