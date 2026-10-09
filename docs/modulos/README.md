# Módulos de negocio

> **Normativo.** Índice y estado de cada capacidad de STOCK. Última verificación: 2026-10-09.
> El estado sale del código, no de tablas de progreso antiguas.

## Qué significa cada columna

- **Documentado**: el módulo tiene su README con la plantilla.
- **Backend**: las reglas, las rutas y las pruebas existen en el repo.
- **Web / App**: existe la pantalla. No significa que el flujo esté recorrido contra el backend.

## Estado

| Módulo | Capacidad | Documentado | Backend | Web | App |
|---|---|---|---|---|---|
| [M01](M01-catalogo/README.md) | Catálogo: productos, ubicaciones, bodegas | Sí | Sí | `ProductsPage`, `LocationsPage` | `locations` |
| [M02](M02-existencias/README.md) | Existencias, kardex, ajustes y reportes | Sí | Sí | `InventoryPage`, `KardexPage`, `ReportsPage` | `inventory` |
| [M03](M03-recepcion/README.md) | Recepción: facturas y discrepancias | Sí | Sí | `InvoicesPage`, `InvoiceDetailPage`, `PendingApprovalPage` | `invoices` |
| [M04](M04-surtido/README.md) | Surtido: órdenes y empaquetado | Sí | Sí, salvo el envío al ERP | `OrdersPage`, `OrderDetailPage` | `orders` |
| [M05](M05-traslados/README.md) | Traslados | Sí | Sí | `TransfersPage` | No hay |
| [M06](M06-etiquetas/README.md) | Etiquetas e impresión | Sí | Sí | `LabelsPage` | `labels` |
| [M07](M07-usuarios-accesos/README.md) | Usuarios y accesos | Sí | Parcial: sesiones y permisos sí; usuarios, solo lectura | `UsersPage` | `auth` |
| [M08](M08-auditoria/README.md) | Auditoría | Sí | Sí | `AuditPage` | No hay |
| [M09](M09-integracion-externa/README.md) | Integración externa: ERP y `kardo` | Parcial | No | No aplica | No aplica |

## Cobertura en navegador

`web/e2e/flow.spec.ts` tiene 18 pruebas contra el backend real. No están en CI y no se
ejecutaron al escribir esto. Por módulo:

| Módulo | Pruebas | Qué cubren |
|---|---|---|
| M02 | 2 | Reportes con datos del servidor; alarma de descuadre |
| M04 | 4 | Listado, alta de orden, empaque sin tocar inventario |
| M05 | 2 | Deuda visible en tránsito; traslado despachado |
| M06 | 3 | Cola de etiquetas; validación de plantilla; previsualización |
| M07 | 1 | Usuarios en modo consulta |
| M08 | 2 | Bitácora con canal y permiso; filtro |
| Acceso | 4 | Sesión, credenciales, recarga |

M01 (catálogo) y M03 (recepción) no tienen prueba de navegador.

## Lo que no está en esta tabla

- **Salud del sistema** (`GET /v1/health`): infraestructura, no un módulo. Ver [api/salud.md](../api/salud.md).
- **Reportes** (`/v1/reports/*`): viven en M02 porque leen de sus existencias y su kardex.
- **Configuración de acceso** (roles, matriz, políticas): [seguridad/roles-permisos.md](../seguridad/roles-permisos.md).

## Plantilla

Cada módulo sigue [plantillas/plantilla-modulo.md](../plantillas/plantilla-modulo.md): objetivo,
alcance, reglas con su archivo, modelo de datos, API, permisos, bitácora, pruebas y criterios de aceptación.
