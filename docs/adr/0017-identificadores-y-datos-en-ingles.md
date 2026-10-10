# ADR-0017 — Identificadores y datos en inglés
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-10.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-10 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |
| **Complementa a** | [ADR-0004](0004-nombres-en-ingles-comentarios-en-espanol.md) |

## Contexto

El ADR 0004 dejó dos excepciones en español: las funciones del motor de inventario y los
valores de código que viven en la base y en el contrato (roles, bodegas, ubicaciones). Eso
dejó códigos como `OPERADOR_ALMACEN`, `JEFE_BODEGA`, `PRINCIPAL`, `RECEPCION` o `SINUBICAR`
en la API, en los datos y en la interfaz. El dueño del proyecto pidió que todo lo que sea
español pase a inglés.

## Decisión

- **Claves de rol, códigos de bodega y códigos de ubicación van en inglés**, en la base, en el
  código, en las pruebas y en el contrato:

  | Antes | Ahora |
  |---|---|
  | `OPERADOR_ALMACEN` | `WAREHOUSE_OPERATOR` |
  | `JEFE_BODEGA` | `WAREHOUSE_MANAGER` |
  | `GERENTE_OPERACIONES` | `OPERATIONS_MANAGER` |
  | `PRINCIPAL` | `MAIN` |
  | `RECEPCION` | `RECEIVING` |
  | `SINUBICAR` (visible: `SIN-UBICAR`) | `UNASSIGNED` |
  | `SUCURSAL` (solo en pruebas) | `BRANCH` |

- **Los nombres visibles de roles** (`displayName`, `oneLiner`) y de la bodega por defecto van en
  inglés. Son texto de interfaz; la interfaz web los traduce con i18n, y la base los guarda en
  inglés para que la API responda lo mismo que el código.
- **Los cambios de datos van en una migración nueva** (`V16__english_identifiers.sql`). Las
  migraciones aplicadas no se editan: cambiarlas rompe la suma de verificación de Flyway.
- **Las tablas de roles se renombran por `id`**, así las concesiones y los usuarios ya asignados
  siguen apuntando al mismo rol.

## Consecuencias

- Una base que ya arrancó con las claves viejas se corrige al aplicar `V16`; `SchemaGuard`
  exige la versión 16.
- **Quedan en español, por ahora**: los comentarios de código, la documentación de `docs/`, los
  mensajes de error que devuelve la API (el operador los lee, ADR-0004) y los nombres de las
  funciones PL/pgSQL del motor (`despachar_traslado`, `recalcular_total_producto`…). Renombrarlas
  exige reescribir sus cuerpos porque se llaman entre sí; queda como paso aparte.
- La interfaz web no muestra estos códigos en español: traduce cada clave con i18n.
