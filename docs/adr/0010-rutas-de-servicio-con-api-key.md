# ADR-0010 — Rutas de sistema con clave de servicio, no con usuario

> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 (decisión B8 del plan original) |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El legacy tenía tres rutas sin autenticación: `GET /kardo` (importa facturas),
`PATCH /prints` (marca etiquetas como impresas) y `DELETE /invoices/detail/:id` (borrado
físico). Las dos primeras las usan sistemas, no personas: la impresora Zebra y el ERP.

## Decisión

- La confirmación de impresión (`PATCH /v1/print-jobs/{id}`) se autentica con una
  **clave de servicio**, no con un token de usuario. La tabla `service_api_keys` la guarda
  (`V2__audit_and_traceability.sql`); el uso está en `label/LabelService.kt`.
- Una etiqueta queda `PENDING` hasta que la impresora confirma, y reintenta si la
  impresora está apagada (R-76).
- `DELETE` de una factura **no** se conserva como ruta abierta: se cierra con
  autenticación. Era un descuido, no una necesidad de sistema.
- La clave de servicio no lleva permisos de usuario ni alcance de bodega.

## Consecuencias

- Hay un tipo de credencial más que rotar y custodiar.
- El ERP no tiene todavía una clave definida: el contrato del envío está en
  [ADR-0013](0013-ordenes-al-erp-con-reintentos.md) y su autenticación queda pendiente.
- Cada uso de una clave de servicio queda en la bitácora con el sistema que llamó.

## Alternativas descartadas

- **Dejar las rutas abiertas.** Es el hallazgo S1 del legacy.
- **Dar a la impresora una cuenta de usuario.** Mezcla una identidad humana con un
  dispositivo y hace que sus permisos se revisen como si fueran de una persona.
