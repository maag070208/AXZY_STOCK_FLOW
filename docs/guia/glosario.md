# Glosario
> **Referencia.** Términos del dominio tal como los usa el código. Última verificación: 2026-10-09.

| Término | Significado | Dónde se define |
|---|---|---|
| **Bodega** (`warehouse`) | Almacén físico. Una por defecto en la operación actual. | `V3__inventory_domain.sql` |
| **Ubicación** (`location`) | Lugar dentro de una bodega: estantería, recepción o `SIN-UBICAR`. | `V3__inventory_domain.sql`, `V5__unassigned_location_kind.sql` |
| **Ubicación de recepción** (`kind = RECEIVING`) | La ubicación temporal donde queda lo recibido y aún no ubicado. Reemplaza la antigua pseudo-ubicación `ADMIN`. | [ADR-0009](../adr/0009-ubicacion-de-recepcion-real.md) |
| **Producto** (`product`) | Artículo con código único (`product_code`). `XN` y `X` son SKUs distintos. | [ADR-0011](../adr/0011-catalogo-fuente-maestra.md) |
| **Existencias** (`stock`) | Cantidad de un producto en una ubicación. Es una proyección, no un número que se edite. | `V3__inventory_domain.sql` (`stock_by_location`) |
| **Kardex** | Libro de movimientos, append-only. Corregir es añadir un movimiento nuevo. | `V3__inventory_domain.sql` (`kardex`) |
| **Tránsito** (`stock_in_transit`) | Mercancía despachada en un traslado que aún no se recibe. | `V3__inventory_domain.sql`, `V9__transfer_lines.sql` |
| **Ajuste** (`adjustment`) | Diferencia de existencias con motivo obligatorio. Una sola regla (`AdjustStock`) con cuatro orígenes. | [ADR-0008](../adr/0008-discrepancias-y-merma.md) |
| **Discrepancia** | Línea de factura cuya cantidad física no coincide con la facturada. Bloquea la ubicación hasta aprobarse. | [ADR-0008](../adr/0008-discrepancias-y-merma.md) |
| **Factura** (`invoice`) | Documento de recepción con líneas. Se cierra cuando ninguna línea queda pendiente. | `V3__inventory_domain.sql` |
| **Línea** (`invoice_line`) | Un producto dentro de una factura, con su estado (`PENDING`, `CAPTURED`, `DISCREPANCY`, `APPROVED`, `LOCATED`). | `V3__inventory_domain.sql` |
| **Orden** (`order`) | Pedido a surtir. Su estado va de `PENDING` a `COMPLETED`. | `V3__inventory_domain.sql` |
| **Surtido** | Empaquetado de una orden línea por línea. | [api/surtido.md](../api/surtido.md) |
| **Traslado** (`transfer`) | Movimiento de existencias entre ubicaciones o bodegas, con estados `DRAFT` → `DISPATCHED` → `RECEIVED`. | `V3__inventory_domain.sql`, `V9__transfer_lines.sql` |
| **Plantilla de etiqueta** (`label_template`) | Formato de etiqueta por tipo. | `V3__inventory_domain.sql` |
| **Trabajo de impresión** (`print_job`) | Etiqueta enviada a imprimir. Queda `PENDING` hasta que la impresora confirma. | [ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md) |
| **Alcance** (`scope`) | Hasta dónde llega un permiso: `NONE`, `OWN`, `AREA` (su bodega) o `ALL`. | [seguridad/roles-permisos.md](../seguridad/roles-permisos.md) |
| **Segregación de funciones** | Quien captura una línea no puede aprobar su discrepancia. | [ADR-0008](../adr/0008-discrepancias-y-merma.md) |
| **Legacy** | El sistema anterior en `BASE/`. | [migracion/legacy-inventario.md](../migracion/legacy-inventario.md) |
| **Conteo físico** | Recuento real de existencias, previo al corte. | [ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md) |
