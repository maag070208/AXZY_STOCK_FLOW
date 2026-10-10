# Plan de rehacer StockFlow: API en Kotlin con Spring Boot, app KMP y web React
> **Plan.** Qué se reconstruye, en qué orden y qué decisiones quedan abiertas. Es el documento
> que manda sobre la secuencia; los normativos (arquitectura, flujos, ADR) describen el destino.
> Última revisión: 2026-10-09.

> **Desglose por módulo y orden de construcción:** [PLAN-IMPLEMENTACION.md](PLAN-IMPLEMENTACION.md).

## 1. Punto de partida

Al empezar, el repositorio tenía solo documentación y el legacy (`BASE/`). El código anterior (Ktor,
Exposed, Compose) estaba en el historial de git y sirvió de **referencia de comportamiento**, no de
base de código. Lo que se conservó:

| Se conserva | Dónde está | Por qué |
|---|---|---|
| Reglas de negocio (invariantes, estados, permisos) | `docs/adr/0005`–`0013`, `docs/modulos/` | Son decisiones del negocio, no del framework |
| Esquema de datos y motor SQL (funciones de inventario) | `db/migrations/V1`–`V15` | El motor vive en la base; V1–V9 vienen del historial y V10–V15 se escribieron después |
| Contrato HTTP | `docs/api/` | Lo consumen la app y la web |
| Diseño visual | `docs/guia/identidad-visual.md` | Sigue siendo la referencia de la web |

Lo que **no** se conservó es la arquitectura de Ktor (`docs/historico/`): el cableado a mano, el stack
de versiones y las decisiones tomadas por el framework anterior.

**Estado al 2026-10-10.** La API existe y corre en Compose, con 752 pruebas en verde y el esquema en
V15. El avance módulo a módulo está en [PLAN-IMPLEMENTACION.md](PLAN-IMPLEMENTACION.md). La app y la web
siguen en curso.

## 2. Destino

| Parte | Tecnología | Arquitectura |
|---|---|---|
| **API** | Kotlin + Spring Boot, PostgreSQL 16, Docker | Clean Architecture con inyección de dependencias por constructor ([arquitectura/api-spring.md](../arquitectura/api-spring.md)) |
| **App** | Kotlin Multiplatform (Android e iOS) | Vertical slice con núcleo compartido ([arquitectura/app-kmp.md](../arquitectura/app-kmp.md)) |
| **Web** | React 19 + Vite + TypeScript + AXZY UI System | Feature-Sliced Design ([arquitectura/web-fsd.md](../arquitectura/web-fsd.md)) |
| **Base de datos** | PostgreSQL 16 | Migraciones versionadas con Flyway ([arquitectura/infraestructura.md](../arquitectura/infraestructura.md)) |
| **Despliegue local** | Docker Compose | Un comando levanta base, migraciones, API y web ([arquitectura/infraestructura.md](../arquitectura/infraestructura.md)) |

Los flujos que debe cumplir cada parte están en [flujos/](../flujos/README.md).

## 3. Requisitos que vienen de la revisión del 9 de octubre

La revisión del backend anterior encontró defectos que el plan **obliga** a evitar. Cada uno
tiene una prueba que lo impide:

| # | Requisito | Prueba que lo impide |
|---|---|---|
| Q1 | El total por producto se recalcula con el producto bloqueado (`SELECT … FOR UPDATE`); dos movimientos concurrentes no pueden perder una escritura. | Prueba de concurrencia con dos transacciones reales (ver [flujos/existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §5) |
| Q2 | Una exportación al ERP **nunca** se registra como exitosa sin un envío real. Mientras no exista el cliente, el intento queda `FAILED` con motivo `ERP_NOT_CONNECTED`. | Prueba de la exportación sin ERP ([flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md) §5) |
| Q3 | La confirmación de una orden bloquea la orden y usa una clave de idempotencia; un doble envío no descuenta dos veces. | Prueba de doble confirmación concurrente ([flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md) §5) |
| Q4 | Todas las migraciones se aplican en el mismo orden en todos los entornos; ningún entorno arranca con migraciones pendientes sin que se note. | Migración de arranque de Compose y comprobación de `flyway_schema_history` ([arquitectura/infraestructura.md](../arquitectura/infraestructura.md) §4) |
| Q5 | Las pruebas de integración corren contra PostgreSQL real, no se saltan en silencio. | Una prueba que falla si no hay base (heredada del canario `PostgresGuardTest`) |
| Q6 | El kardex solo crece: ninguna operación modifica ni borra un movimiento. Corregir es añadir un movimiento compensatorio. | Prueba A9 de [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7; análisis de origen C1 en [analisis/legacy-entradas-salidas-ajustes-traslados.md](../analisis/legacy-entradas-salidas-ajustes-traslados.md) |
| Q7 | El saldo lo cambia la base con bloqueo. Ninguna aplicación calcula un saldo absoluto y lo escribe (lectura-modificación-escritura). | Pruebas A3 y A4 de [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7; análisis C2 y C5 |
| Q8 | Una salida descuenta lo que falta por surtir (`quantity − supplied_quantity`), dentro de una transacción que bloquea la orden, con una sola operación de confirmación. Ninguna escritura queda sin `await`. | Prueba S2 y S3 de [surtido-y-erp.md](../flujos/surtido-y-erp.md) §7; análisis C3 y C6 |
| Q9 | La disponibilidad se comprueba dentro de la misma transacción que escribe, con el saldo bloqueado. Nunca con una lectura previa. | Prueba A3 de [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7; análisis C4 |
| Q10 | El saldo sale de la proyección, no del historial. Un listado hace un número fijo de consultas, sin N+1. | Pruebas de rendimiento de la fase 5; análisis A1 a A4 |

## 4. Decisiones

Las decisiones marcadas **Tomada** ya están en un ADR. Las **Propuesta** necesitan
confirmación antes de la fase donde se aplican.

| # | Decisión | Estado | Dónde |
|---|---|---|---|
| D1 | API en Kotlin con Spring Boot | Tomada | [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) |
| D2 | Inyección de dependencias por constructor, cableado solo en el módulo de arranque | Tomada | [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) |
| D3 | PostgreSQL 16 con Flyway; el motor de inventario sigue en funciones SQL | Tomada | [ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md) |
| D4 | Despliegue local con Docker Compose | Tomada | [ADR-0016](../adr/0016-docker-compose-para-el-entorno.md) |
| D5 | ERP: sin cliente, la exportación falla con motivo; no se simula el éxito | Tomada (Q2) | [flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md) |
| D6 | **Dominio compartido** entre la API y la app (módulo KMP con las reglas) | **Tomada (2026-10-10): sí** | [arquitectura/dominio-kmp.md](../arquitectura/dominio-kmp.md) |
| D7 | Reutilizar las migraciones `V1`–`V10` como esquema inicial de Flyway, en lugar de rediseñar el esquema | **Tomada (2026-10-10): sí** | §5, fase 1 |
| D8 | Versiones de la API: Spring Boot 4.1.x, Kotlin 2.4.20, JDK 25 LTS | **Tomada (2026-10-10)**; confirmar en la fuente oficial antes de fijar el lockfile | [arquitectura/stack.md](../arquitectura/stack.md) |
| D9 | La web mantiene FSD | **Tomada (2026-10-10): sí**, con la regla de capas impuesta por el linter | [arquitectura/web-fsd.md](../arquitectura/web-fsd.md) |
| D10 | Identificador del ERP (`external_id`) | **Pendiente**: depende de lo que el ERP acepte | [ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md) |

## 5. Fases

Cada fase termina con un criterio que se puede comprobar. No se empieza la siguiente sin
cumplir el de la anterior.

| Fase | Entrega | Criterio de salida |
|---|---|---|
| **0. Documentación y decisiones** | Este plan, los normativos y los ADR nuevos. D6–D10 resueltas. | Revisión de los normativos sin decisiones pendientes que bloqueen la fase 1 |
| **1. Infraestructura** | Compose con PostgreSQL 16 y el servicio `migrate`. Flyway con `V1`–`V10` (D7). | `docker compose up` deja la base migrada; `flyway_schema_history` lista las 10 versiones |
| **2. API base** | Proyecto Gradle multimódulo, arranque, salud, configuración por variables sin valores por defecto inseguros, manejo de errores. | `GET /v1/health` responde; arranque falla si falta una variable obligatoria |
| **3. Identidad** | Login, refresh con rotación, logout, cambio de contraseña, bloqueo, RBAC + ABAC denegando por defecto. | Pruebas de los flujos de [autenticación](../flujos/autenticacion-y-permisos.md) en verde |
| **4. Catálogo y bodegas** | Productos, ubicaciones, bodegas e importación estricta del Excel. | Importación con una fila inválida rechaza el archivo entero |
| **5. Existencias** | Motor de inventario, kardex, ajustes, ubicar y mover, reportes de conciliación. Q1 en vigor. | Prueba de concurrencia Q1 en verde |
| **6. Recepción** | Facturas, captura, discrepancias, aprobación por factura, segregación de funciones. | Ninguna línea en discrepancia se puede ubicar |
| **7. Surtido y ERP** | Órdenes, empaque, faltantes, confirmación, exportación. Q2 y Q3 en vigor. | Pruebas Q2 y Q3 en verde |
| **8. Traslados** | Entre ubicaciones y entre bodegas, tránsito, recepción. | Recepción de traslado cubierta por prueba (hoy no lo está) |
| **9. Etiquetas y auditoría** | Plantillas, cola de impresión con clave de servicio, bitácora inmutable. | Cada escritura de los flujos deja registro |
| **10. App KMP** | Núcleo compartido con las reglas, pantallas de piso (recepción, ubicar, surtir, escanear). | Compila en Android e iOS; flujo de recepción probado en dispositivo |
| **11. Web React** | Administración, supervisión, existencias, kardex, reportes. | Flujos principales cubiertos por pruebas de navegador en CI |

Las fases 10 y 11 empiezan cuando el contrato de la API de la fase anterior está estable.

## 6. Qué se necesita para empezar la fase 1

1. Resolver D6 y D7 (ver §4).
2. Fijar las versiones de D8 y anotarlas en [arquitectura/stack.md](../arquitectura/stack.md).
3. Tener PostgreSQL 16 y Docker disponibles en la máquina de desarrollo.

## 7. Riesgos conocidos

| Riesgo | Mitigación |
|---|---|
| Reescribir las reglas al migrar al nuevo framework y que cambie el comportamiento | Las pruebas de los flujos se escriben antes que el código, a partir de [flujos/](../flujos/README.md) |
| El dominio compartido (D6) complica el build de tres partes | Se decide en fase 0; si se rechaza, la API duplica las reglas y se documenta cuál es la copia de referencia |
| El ERP real llega tarde y la exportación queda en `FAILED` mucho tiempo | Es el comportamiento honesto y está previsto por ADR-0013; el reintento manual existe desde la fase 7 |
| Las versiones de Spring Boot o Compose cambian antes de empezar | D8 se fija en fase 0 con la versión vigente en el momento y se registra la fecha |
