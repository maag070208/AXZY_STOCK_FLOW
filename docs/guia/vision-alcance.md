# Visión y alcance
> **Normativo.** Qué construye STOCK, para quién y qué queda fuera. Última verificación: 2026-10-09.

## Problema

Almacén Integral (Cascar) lleva el inventario de una operación con bodegas, ubicaciones y
tres clientes: una web administrativa, una app de piso con escáner y la API que las
sirve. El sistema actual es el legacy de `BASE/` (ver [migracion/legacy-inventario.md](../migracion/legacy-inventario.md)).
Sus defectos de fondo son de modelo: el total por producto no sabe dónde está el stock,
el libro de movimientos se muta y el control de acceso tiene bypasses. La reescritura
corrige eso sin cambiar los flujos que la operación ya usa.

## Objetivos

1. Un inventario que **cuadre por construcción**: cada cambio de existencias es un
   movimiento nuevo en el kardex, y el saldo por ubicación es una proyección
   ([ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md), [arquitectura/dominio-kmp.md](../arquitectura/dominio-kmp.md)).
2. Las reglas de negocio viven en un solo lugar, `stock-domain`, y las comparten el
   backend y la app.
3. Control de acceso por rol y por alcance, denegando por defecto
   ([ADR-0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md)).
4. Cada escritura queda en la bitácora con quién, cuándo y por qué
   ([seguridad/auditoria.md](../seguridad/auditoria.md)).

## Alcance

| Dentro | Fuera (por ahora) |
|---|---|
| Catálogo de productos, ubicaciones y bodegas, con importación del Excel inicial | Conteo físico como proyecto propio ([R-73](../migracion/cutover.md)) |
| Existencias por ubicación, kardex, ajustes, ubicar y mover | Facturación electrónica y contabilidad |
| Recepción: facturas, captura física, discrepancias con aprobación por factura | Integración con el ERP en producción: el contrato está, el envío real no ([ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md)) |
| Surtido: órdenes, empaquetado, faltantes | Sincronización periódica del catálogo con otros sistemas ([ADR-0011](../adr/0011-catalogo-fuente-maestra.md)) |
| Traslados entre ubicaciones y entre bodegas | Operación real con más de una bodega (el esquema lo soporta, [ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md)) |
| Etiquetas: plantillas, impresión, reimpresión | iOS probado en dispositivo ([arranque](../../ARRANQUE.md)) |
| Usuarios, roles, permisos, sesiones, bitácora y reportes | |

## Usuarios

| Quién | Dónde trabaja | Qué hace |
|---|---|---|
| Operador de piso | App móvil (`mobile-app/`) | Recibe, ubica, surte, escanea etiquetas |
| Supervisor | Web (`web/`) | Aprueba discrepancias por factura, consulta existencias y kardex |
| Administrador | Web | Usuarios, roles, catálogo, bodegas, bitácora |
| Sistemas externos | API con clave de servicio | Imprime etiquetas (Zebra), consulta el ERP ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)) |

Los permisos concretos están en [seguridad/roles-permisos.md](../seguridad/roles-permisos.md).
