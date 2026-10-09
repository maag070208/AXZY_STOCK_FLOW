# ADR-0007 — Multi-bodega desde el esquema, operando con una

> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El modelo del legacy tenía un único total por producto (`Store.stock`) y deducía la ubicación
a posteriori compensando con restas; de ahí el descuadre
(`backend/src/main/resources/db/migration/V3__inventory_domain.sql`). El sistema nuevo nace
con la proyección por ubicación como fuente de verdad y con la bodega como entidad de primera
clase.

El negocio confirmó que habrá más de una bodega. La decisión, por tanto, no es si habrá
varias, sino cuándo se paga el coste de modelarlas. Se paga en el esquema desde el principio:
cambiar el esquema de inventario más adelante obliga a migrar datos ya en uso.

## Decisión

- El esquema soporta N bodegas desde `V3__inventory_domain.sql`: la tabla `warehouses`,
  `locations.warehouse_id`, `stock_by_location.warehouse_id`, `transfers` con bodega de origen
  y de destino, y `stock_in_transit`.
- La operación arranca con una sola bodega: el sembrado de V3 crea una con
  `is_default = true`, y el índice único parcial `warehouses_one_default_idx` impide que haya
  más de una por defecto (`V3__inventory_domain.sql`).
- Añadir la segunda bodega es un `INSERT`. **No requiere migración.**
- Un traslado entre la misma bodega está prohibido en la cabecera
  (`transfers_different_warehouses`) y en el tránsito (`transit_warehouses_differ`,
  `V9__transfer_lines.sql`). El dominio lo comprueba antes de tocar la base para poder
  devolver un error que explique el campo en lugar de un error de restricción
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`).
- Las líneas del traslado y el vínculo del tránsito con su traslado viven en
  `V9__transfer_lines.sql`: la tabla `transfer_lines` y `stock_in_transit.transfer_id`,
  para poder atribuir la deuda de un traslado no recibido.
- Toda operación nueva que toque existencias declara `warehouse_id` y respeta las
  restricciones de bodegas distintas.
- El alcance `AREA` de permisos significa «su bodega» y se resuelve contra las bodegas de la
  persona, no contra un rol por bodega
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`,
  `RoleDefinitions.kt`).

## Consecuencias

Cuando llegue la segunda bodega no hay migración de datos ni cambio de esquema. Los reportes
por bodega se resuelven con el `warehouse_id` desnormalizado en `stock_by_location`
(`V3__inventory_domain.sql`).

Se paga que ninguna consulta puede asumir una sola bodega desde hoy, y que cada tabla nueva
de existencias debe llevar su bodega y su restricción. El total por producto sigue siendo una
proyección de las ubicaciones más el tránsito, no un número que se mantenga a mano
(`V3__inventory_domain.sql`, `V9__transfer_lines.sql`).

Reglas operativas que se derivan:

- No se codifica la bodega por defecto: ni su `id`, ni su código `PRINCIPAL`. `is_default` es
  una comodidad de captura, no una identidad (`V3__inventory_domain.sql`).
- La mercancía en tránsito **sigue contando** en el inventario total. Modelarla como dos
  movimientos instantáneos haría que el total bajara durante el viaje y los reportes
  mentirían (`V3__inventory_domain.sql`).
- Un traslado nunca recibido es una deuda visible y hay que poder atribuirla a su traslado
  (`V9__transfer_lines.sql`).
- Deuda ya corregida, que conviene recordar: V3 creó `transfers` sin líneas y
  `stock_in_transit` sin `transfer_id`, así que un traslado no podía decir qué llevaba ni
  atribuirse una fila de tránsito (`V9__transfer_lines.sql`).

## Alternativas consideradas

- **Modelar una sola bodega y migrar cuando llegue la segunda.** La migración tocaría las
  tablas de existencias con datos dentro, que es el coste que esta decisión evita
  (`V3__inventory_domain.sql`).
- **Tratar la bodega como una constante o un enum.** No puede crecer y no permite dar de alta
  la segunda sin tocar código.
- **Filtrar por bodega solo en los reportes.** El total seguiría siendo uno y un descuadre no
  se podría atribuir a ninguna bodega (`V3__inventory_domain.sql`).

## Referencias

- `backend/src/main/resources/db/migration/V3__inventory_domain.sql`
- `backend/src/main/resources/db/migration/V9__transfer_lines.sql`
- `backend/src/main/kotlin/dev/axzy/stock/backend/catalog/warehouse/WarehouseRoutes.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/transfer/TransferService.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/RoleDefinitions.kt`
- [roles-permisos.md](../seguridad/roles-permisos.md)
