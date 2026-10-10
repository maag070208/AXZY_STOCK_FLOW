# Documentación de STOCK
> **Índice.** Por dónde empezar y qué documento manda en cada tema. Última revisión: 2026-10-09.

STOCK es el sistema de almacén de Almacén Integral (Cascar). Se reconstruye desde cero con:

- **API**: Kotlin con Spring Boot, en capas, con inyección de dependencias por constructor.
- **Base de datos**: PostgreSQL 16, con migraciones Flyway y el motor de inventario en SQL.
- **Entorno**: Docker Compose.
- **App**: Kotlin Multiplatform para Android e iOS.
- **Web**: React con AXZY UI System `^1.4.7`.

El código anterior (Ktor) está en el historial de git y en `historico/`. El legacy de Node está en `BASE/`.

## Por dónde empezar

1. [plan/PLAN-REHACER.md](plan/PLAN-REHACER.md): qué se construye, en qué fases y qué decisiones siguen abiertas.
2. [arquitectura/general.md](arquitectura/general.md): cómo se relacionan las partes.
3. [flujos/README.md](flujos/README.md): los procesos que debe cumplir el sistema.

## Qué manda en cada tema

| Tema | Documento normativo | Notas |
|---|---|---|
| Plan y fases | [plan/PLAN-REHACER.md](plan/PLAN-REHACER.md) | Manda sobre el orden de trabajo |
| Capas y DI de la API | [arquitectura/api-spring.md](arquitectura/api-spring.md) | Incluye las reglas de rendimiento (§11) |
| Entorno y migraciones | [arquitectura/infraestructura.md](arquitectura/infraestructura.md) | |
| Versiones | [arquitectura/stack.md](arquitectura/stack.md) | Las versiones pendientes se fijan en la fase 0 |
| App | [arquitectura/app-kmp.md](arquitectura/app-kmp.md) | |
| Web | [arquitectura/web-react.md](arquitectura/web-react.md) y [arquitectura/web-fsd.md](arquitectura/web-fsd.md) | El primero, para el UI system; el segundo, para las capas |
| Dominio compartido | [arquitectura/dominio-kmp.md](arquitectura/dominio-kmp.md) | Pendiente de D6 |
| Procesos | [flujos/](flujos/README.md) | Manda sobre los módulos cuando se contradicen |
| Decisiones | [adr/](adr/) | Un ADR por decisión; los reemplazos están marcados |
| Contrato HTTP | [api/](api/convenciones.md) | Lo consume la app y la web |
| Seguridad | [seguridad/](seguridad/roles-permisos.md) | |

## Decisiones (ADR)

| ADR | Tema | Estado |
|---|---|---|
| [0001](adr/0001-clean-architecture-en-la-api.md) | Clean Architecture en la API | Reemplazo parcial por 0014 (framework) |
| [0002](adr/0002-fsd-en-la-web.md) | FSD en la web | Vigente |
| [0003](adr/0003-vertical-slice-en-la-app.md) | Vertical slice en la app | Vigente |
| [0004](adr/0004-nombres-en-ingles-comentarios-en-espanol.md) | Nombres en inglés, comentarios en español | Vigente |
| [0005](adr/0005-errores-devuelto-no-lanzado.md) | Errores devueltos, no lanzados | Vigente |
| [0006](adr/0006-rbac-mas-abac-denegar-por-defecto.md) | RBAC + ABAC, denegar por defecto | Vigente |
| [0007](adr/0007-multi-bodega-desde-el-esquema.md) | Multi-bodega desde el esquema | Vigente |
| [0008](adr/0008-discrepancias-y-merma.md) | Discrepancias y merma | Vigente |
| [0009](adr/0009-ubicacion-de-recepcion-real.md) | Ubicación de recepción real | Vigente |
| [0010](adr/0010-rutas-de-servicio-con-api-key.md) | Rutas de servicio con clave de API | Vigente |
| [0011](adr/0011-catalogo-fuente-maestra.md) | Catálogo como fuente maestra | Vigente |
| [0012](adr/0012-conciliar-stock-heredado-con-conteo.md) | Conciliar stock heredado con conteo | Vigente |
| [0013](adr/0013-ordenes-al-erp-con-reintentos.md) | Órdenes al ERP con reintentos | Vigente; el cliente del ERP no existe |
| [0014](adr/0014-api-spring-boot-clean-architecture.md) | API en Spring Boot, en capas, con DI | Aceptada |
| [0015](adr/0015-postgresql-flyway-motor-en-sql.md) | PostgreSQL con Flyway y motor en SQL | Aceptada |
| [0016](adr/0016-docker-compose-para-el-entorno.md) | Docker Compose, migraciones como servicio | Aceptada |

## Reglas de negocio por módulo

Los módulos describen cada área con su alcance, reglas y pruebas. Sus rutas de código apuntan al
código anterior y se remapean al implementar: [modulos/](modulos/README.md).

## Análisis

- [analisis/legacy-entradas-salidas-ajustes-traslados.md](analisis/legacy-entradas-salidas-ajustes-traslados.md):
  hallazgos de rendimiento e integridad del legacy en entradas, salidas, ajustes y traslados, con su
  archivo y línea, y la corrección prevista en el diseño nuevo.

## Histórico

`historico/` guarda los documentos del diseño anterior (Ktor, Compose de la API, planes de fases
pasadas). Sirven como referencia de lo que ya se decidió; no describen el sistema que se construye.
