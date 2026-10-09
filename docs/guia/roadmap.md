# Roadmap

> **Plan.** Orden de construcción y estado real, derivado del código. Última verificación: 2026-10-09.
> El estado de cada módulo se mide en [modulos/README.md](../modulos/README.md); aquí solo el orden.

## Estado por fase

| Fase | Qué | Estado | Evidencia |
|---|---|---|---|
| 0 · Dominio | `stock-domain` puro, con guardias de arquitectura | Hecho | `stock-domain/` y sus pruebas |
| 1 · Backend núcleo | Identidad, sesiones, catálogo, ubicaciones, bodegas. La administración de usuarios es solo de lectura | Hecho, con la administración de usuarios pendiente | `V1`, `V4`, rutas de `catalog/` e `identity/` |
| 2 · Recepción e inventario | Motor de existencias, ajustes, kardex, facturas, aprobación | Hecho | rutas de `receiving/` e `inventory/` |
| 3 · Surtido | Órdenes, empaquetado, faltantes | Hecho en el backend; envío al ERP pendiente | `order/OrderRoutes.kt` |
| 3-bis · Traslados | Traslados y tránsito | Hecho en el backend | `transfer/TransferRoutes.kt`, `V9` |
| 4 · Etiquetas | Plantillas, impresión, reimpresión | Hecho en el backend | `label/LabelRoutes.kt` |
| 5 · Web | 19 pantallas | En curso: pantallas reales, flujos sin recorrer contra el backend | `web/src/pages/` |
| 6 · Móvil | Build KMP, escáner, APK | En curso: sin conectar al API de forma verificada | `mobile-app/` |
| 7 · Migración | Catálogo importado, reconciliación y corte | En curso: la carga del catálogo no está verificada en local; conteo físico pendiente | [migracion/](../migracion/) |
| 8 · Operación | Respaldos, despliegue, OWASP | Pendiente | [operacion/](../operacion/) |

## Siguiente paso

1. Conectar y recorrer los flujos de la web contra el backend. Es lo que impide decir que un
   módulo está terminado.
2. Conteo físico y reconciliación antes del corte ([ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md)).
3. Envío real de órdenes al ERP con reintentos ([ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md)).
4. Respaldos y despliegue ([operacion/respaldos.md](../operacion/respaldos.md)).
