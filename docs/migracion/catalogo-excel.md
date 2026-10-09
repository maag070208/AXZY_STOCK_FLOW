# Catálogo del Excel inicial

> **Referencia.** Qué trae el inventario real que se importa al sistema nuevo y cómo se
> importa. Última verificación: 2026-10-09.

## Fuente

Archivo de inventario de 10 de junio de 2024, analizado en
[historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §3.5. Copia
trabajada en `scripts/catalogo-existencias.tsv`; la carga la hace `scripts/importar-catalogo.sh`.

## Perfil de los datos

| Medida | Valor |
|---|---|
| Productos | 1.398 |
| Código de producto | único |
| Unidad de medida | `PZA` en el 100 % |
| Unidades totales | 10.914.130 |
| Nulos | ninguno |

El total es fiable. El desglose por ubicación no viene en el archivo: ver
[ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md).

## Importación

- **Estricta.** Una fila inválida rechaza el archivo entero (`catalog/CatalogImportService.kt`).
  No se corrige adivinando.
- **Stock inicial a `SIN-UBICAR`** ([ADR-0009](../adr/0009-ubicacion-de-recepcion-real.md)).
- **Códigos `X` y `XN` son dos productos** (R-71; [ADR-0011](../adr/0011-catalogo-fuente-maestra.md)).
  Son 201 pares, 180 con el mismo nombre. Antes de dar el corte, comparar en cada par si
  ambos tienen stock alto: si es así, hay que revisar la decisión.

## Lo que el archivo no resuelve

- El desglose por ubicación: se resuelve con conteo físico antes del corte.
- Los productos sin ubicación física conocida: quedan en `SIN-UBICAR` hasta contarse.
