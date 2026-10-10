# ADR-0009 — La ubicación de recepción es una ubicación real
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 (decisión B7 y R-65 del plan original) |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

En el legacy, lo recibido y no ubicado se guardaba en una pseudo-ubicación `ADMIN` que no
era una ubicación real: no tenía id en el mismo sentido que las demás y el stock quedaba
allí sin un lugar físico. Además, ubicar era una mutación del libro, no un movimiento.

## Decisión

- La recepción tiene una ubicación real con su id, marcada `kind = RECEIVING`
  (`V5__unassigned_location_kind.sql`, `V6__unassigned_location_and_inventory_engine.sql`).
- Ubicar es un movimiento nuevo: el total del producto no cambia y el registro histórico
  se conserva (R-65). Esto es consecuencia de [ADR-0008](0008-discrepancias-y-merma.md)
  y del kardex append-only.
- La ubicación de recepción está protegida: no se puede desactivar
  (`RECEIVING_LOCATION_PROTECTED`, `catalog/location/LocationRoutes.kt`).
- El stock inicial de la importación va a una ubicación propia `SIN-UBICAR`, no a la
  recepción (ver [migracion/catalogo-excel.md](../migracion/catalogo-excel.md)).

## Consecuencias

- Un producto recibido y no ubicado tiene un saldo visible en `RECEIVING`. No desaparece.
- La regla de «no ubicar una línea en discrepancia» (ADR-0008) se apoya en que la
  recepción es una ubicación con reglas propias.

## Alternativas descartadas

- **Mantener `ADMIN` como pseudo-ubicación.** Oculta el stock fuera del modelo de ubicaciones.
- **Ubicar mutando el registro de recepción.** Impide reconstruir qué se recibió (B6 del
  plan original).
