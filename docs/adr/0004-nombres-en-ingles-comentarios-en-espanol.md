# ADR-0004 — Nombres en inglés, comentarios en español

> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

Los identificadores se escribían en dos idiomas, y los nombres en español llegaban hasta la
API pública. `comprobar_proyecciones()` devolvía las columnas `total_en_bd`,
`total_esperado` y `diferencia`; el mapper las leía por nombre, el DTO se serializaba con
esas propiedades y el contrato publicaba esos campos (`V8__english_reconciliation_columns.sql`).

Corregirlo no fue un renombrado: en PostgreSQL los nombres de un `RETURNS TABLE` son parte
de la firma de la función y un `CREATE OR REPLACE` con otros nombres de salida falla. Hubo
que soltar y recrear la función, y con ella el `GRANT` y las llamadas
(`V8__english_reconciliation_columns.sql`). El caso demuestra que un nombre en español en la
base no es cosmética: se filtra al cliente.

## Decisión

- Los identificadores van en inglés: variables, métodos, constantes, clases, tablas,
  columnas, valores de enum y códigos de error ([convenciones.md](../guia/convenciones.md) §5.1).
- Solo los comentarios van en español ([convenciones.md](../guia/convenciones.md) §5.1).
- No se traducen los comentarios, los mensajes de error —los lee el operador—, los
  `displayName` (texto de interfaz), los valores de enum que están en la base y en el
  contrato, ni los códigos de error, que son API pública ([convenciones.md](../guia/convenciones.md) §5.1).
- Los nombres de tabla y columna están en inglés y no se tocan ([convenciones.md](../guia/convenciones.md) §5.1). Una
  migración que cambie el nombre de una columna de salida recrea la función
  (`V8__english_reconciliation_columns.sql`).
- La documentación se escribe en español (`docs/README.md`), igual que los comentarios y los
  mensajes de commit.

## Consecuencias

El código se lee igual para cualquiera que llegue, y un campo de la API no se traduce a
mitad de camino entre la base y el cliente.

Se paga disciplina en los bordes: cada campo nuevo de una respuesta se revisa antes de
publicarlo, porque un nombre en español en la base llega al JSON y entonces ya no es un
detalle interno.

Deuda conocida y acotada: las funciones PL/pgSQL del motor de inventario conservan su nombre
en español —`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto` y
`registrar_movimiento` en `V6`; `comprobar_proyecciones` en `V6` y `V8`—. Es una excepción
consciente, documentada en `V8__english_reconciliation_columns.sql`: renombrarlas obliga a
cambiar el `GRANT`, la llamada y el `COMMENT`, y no aporta nada al contrato HTTP. Sus
columnas de salida sí están en inglés.

Reglas operativas que se derivan:

- El catálogo de errores usa identificadores en inglés y mensajes en español
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`).
- Un valor de enum no se traduce porque viaja en la base y en el contrato
  (`backend/src/main/resources/db/migration/V3__inventory_domain.sql`).

## Alternativas consideradas

- **Escribir todo en español.** Es lo que hacía en parte el modelo anterior, y los nombres en
  español acabaron en campos de API pública (`V8__english_reconciliation_columns.sql`).
- **Escribir todo en inglés, comentarios incluidos.** El equipo piensa en español y el motivo
  de una decisión se pierde al traducirlo.
- **Traducir solo la API y dejar la base en español.** Deja dos nombres para el mismo campo y
  obliga a un mapper que traduzca en cada lectura.

## Referencias

- [convenciones.md](../guia/convenciones.md) §5.1, §5.2 y [general.md](../arquitectura/general.md)
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`
- `backend/src/main/resources/db/migration/V3__inventory_domain.sql`
- `backend/src/main/resources/db/migration/V6__unassigned_location_and_inventory_engine.sql`
- `backend/src/main/resources/db/migration/V8__english_reconciliation_columns.sql`
- `gradle/libs.versions.toml`
- `docs/README.md`
