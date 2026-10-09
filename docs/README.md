# Documentación de Almacén Integral

> **Normativo.** Este índice y la convención de tipos de abajo mandan sobre cómo se
> escribe el resto de la documentación. Última verificación: 2026-10-09.

## ¿Primera vez aquí?

1. [README.md](../README.md) — qué es el proyecto y cómo arrancarlo.
2. [arquitectura/general.md](arquitectura/general.md) — las cuatro partes y por qué
   cada una usa una arquitectura distinta.
3. [guia/convenciones.md](guia/convenciones.md) — idioma, comentarios y estilo.
4. La tabla de módulos de negocio de este mismo índice: qué capacidades existen y en
   qué estado están (sus documentos se escriben en la fase 3).

## Convención de tipos de documento

**Cada documento declara su tipo en la línea 2**, con una de estas cuatro palabras:

| Tipo | Qué significa | Manda sobre |
|---|---|---|
| **Normativo** | Cómo se hacen las cosas aquí. Si el código lo contradice, uno de los dos está mal. | Todo |
| **Plan** | Trabajo propuesto o en curso. No describe el estado actual. | Nada |
| **Referencia** | Material de consulta: contrato de API, diccionario de datos, mapa del legacy. | Nada |
| **Histórico** | Lo ya ejecutado o descartado. Se conserva por trazabilidad. | Nada |

Tres reglas que se derivan y que se verifican en cada documento nuevo:

1. **Un normativo gana sobre un plan.** Si un plan contradice un normativo, el plan
   está equivocado.
2. **Cada regla vive en un solo lugar.** Los demás documentos enlazan; no copian.
3. **Cada afirmación técnica cita su archivo de código** (`backend/build.gradle.kts`),
   o no entra en un normativo.

## Estado por área

| Área | Carpeta | Qué contiene | Estado |
|---|---|---|---|
| Guía | [guia/](guia/) | Visión, glosario, convenciones, roadmap | Convenciones y estado de verificación hechos; el resto, fase 3 |
| Arquitectura | [arquitectura/](arquitectura/) | Las cuatro partes, el stack y las reglas de dependencia | Hecho |
| Modelo de datos | [modelo-datos/](modelo-datos/) | Entidad-relación y diccionario de datos | Fase 3 |
| API | [api/](api/) | Contrato por recurso, convenciones, autenticación y errores | Hecho |
| Seguridad | [seguridad/](seguridad/) | Roles y permisos, auditoría, OWASP | Roles, permisos y auditoría hechos; OWASP, fase 6 |
| Módulos | [modulos/](modulos/) | Una carpeta por capacidad de negocio (M01…M09) | Fase 3 |
| Migración | [migracion/](migracion/) | El legacy `BASE/`, el mapa al sistema nuevo y el corte | Fase 4 |
| Operación | [operacion/](operacion/) | Entornos, despliegue, respaldos | Fase 6 |
| Pruebas | [pruebas/](pruebas/) | Estrategia, verificación y lo que no está verificado | Hecho |
| Decisiones | [adr/](adr/) | Un ADR por decisión, numerados | 0001 a 0007 escritos |
| Plantillas | [plantillas/](plantillas/) | La plantilla de módulo | Hecho |
| Referencias | [referencias/](referencias/) | Material de otros proyectos | Fase 7 |
| Histórico | [historico/](historico/) | Planes ejecutados, archivados | Fase 7 |

## Guía

| Documento | Tipo | Estado |
|---|---|---|
| [convenciones.md](guia/convenciones.md) | Normativo | Hecho |
| [lo-no-verificado.md](guia/lo-no-verificado.md) | Referencia | Hecho |
| vision-alcance.md | Normativo | Fase 3 |
| glosario.md | Referencia | Fase 3 |
| roadmap.md | Plan | Fase 3 |

## Arquitectura

| Documento | Tipo | Estado |
|---|---|---|
| [general.md](arquitectura/general.md) | Normativo | Hecho |
| [api-ktor.md](arquitectura/api-ktor.md) | Normativo | Hecho |
| [web-fsd.md](arquitectura/web-fsd.md) | Normativo | Hecho |
| [app-kmp.md](arquitectura/app-kmp.md) | Normativo | Hecho |
| [dominio-kmp.md](arquitectura/dominio-kmp.md) | Normativo | Hecho |
| [stack.md](arquitectura/stack.md) | Referencia | Hecho |
| [migracion-capas.md](arquitectura/migracion-capas.md) | Plan | En curso (la migración sigue abierta) |

## API

| Documento | Tipo | Estado |
|---|---|---|
| [contrato.md](api/contrato.md) — índice, permisos por endpoint y lo que no existe | Referencia | Hecho |
| [convenciones.md](api/convenciones.md) | Normativo | Hecho |
| [autenticacion.md](api/autenticacion.md) | Referencia | Hecho |
| [errores.md](api/errores.md) | Referencia | Hecho |
| [catalogo.md](api/catalogo.md) | Referencia | Hecho |
| [inventario.md](api/inventario.md) | Referencia | Hecho |
| [recepcion.md](api/recepcion.md) | Referencia | Hecho |
| [surtido.md](api/surtido.md) | Referencia | Hecho |
| [traslados.md](api/traslados.md) | Referencia | Hecho |
| [etiquetas.md](api/etiquetas.md) | Referencia | Hecho |
| [reportes.md](api/reportes.md) | Referencia | Hecho |
| [salud.md](api/salud.md) | Referencia | Hecho |

## Seguridad y pruebas

| Documento | Tipo | Estado |
|---|---|---|
| [seguridad/roles-permisos.md](seguridad/roles-permisos.md) | Normativo | Hecho |
| [seguridad/auditoria.md](seguridad/auditoria.md) | Normativo | Hecho |
| seguridad/owasp.md | Referencia | Fase 6 |
| [pruebas/estrategia-pruebas.md](pruebas/estrategia-pruebas.md) | Normativo | Hecho |
| [pruebas/verificacion.md](pruebas/verificacion.md) | Normativo | Hecho |

## Módulos de negocio

Los nueve módulos, con su carpeta y su tabla de estado, se crean en la fase 3 con
[plantillas/plantilla-modulo.md](plantillas/plantilla-modulo.md):

| Módulo | Capacidad | Estado |
|---|---|---|
| M01 | Catálogo: productos, ubicaciones, bodegas, importación | Fase 3 |
| M02 | Existencias: stock por ubicación, kardex, ajustes | Fase 3 |
| M03 | Recepción: facturas, captura física, discrepancias | Fase 3 |
| M04 | Surtido: órdenes y empaquetado | Fase 3 |
| M05 | Traslados: entre ubicaciones y entre bodegas | Fase 3 |
| M06 | Etiquetas: plantillas, impresión, reimpresión | Fase 3 |
| M07 | Usuarios y accesos: roles, permisos, sesiones | Fase 3 |
| M08 | Auditoría: bitácora y trazabilidad | Fase 3 |
| M09 | Integración con el legacy y kardex | Fase 3 |

Los módulos son capacidades de negocio de STOCK. La numeración sirve para enlazar y
no implica orden de implementación.

## Decisiones (ADR)

| ADR | Decisión | Estado |
|---|---|---|
| [0001](adr/0001-clean-architecture-en-la-api.md) | Clean Architecture por feature en la API | Aceptada |
| [0002](adr/0002-fsd-en-la-web.md) | FSD en la web | Aceptada |
| [0003](adr/0003-vertical-slice-en-la-app.md) | Vertical slice en la app móvil | Aceptada |
| [0004](adr/0004-nombres-en-ingles-comentarios-en-espanol.md) | Nombres en inglés, comentarios en español | Aceptada |
| [0005](adr/0005-errores-devuelto-no-lanzado.md) | Un error esperado se devuelve, no se lanza | Aceptada |
| [0006](adr/0006-rbac-mas-abac-denegar-por-defecto.md) | RBAC + ABAC, denegar por defecto | Aceptada |
| [0007](adr/0007-multi-bodega-desde-el-esquema.md) | Multi-bodega desde el esquema, operando con una | Aceptada |

## Pendiente (fases 3 a 7)

| Fase | Qué falta |
|---|---|
| 3 | Documento por módulo (`modulos/M01…M09`), `modelo-datos/` con el diccionario generado de las migraciones, y `guia/vision-alcance.md`, `guia/glosario.md` y `guia/roadmap.md`. |
| 4 | `migracion/`: inventario del legacy, mapa legacy → nuevo, catálogo del Excel y plan de corte. |
| 5 | Los ADR de las decisiones de negocio (B3, B7, B8, R-71, R-73 y similares), que hoy viven en `PLAN_ALMACEN_INTEGRAL.md` y `PLAN_REFACTOR_MEJORAS.md`. |
| 6 | `operacion/` a partir de `ARRANQUE.md` y `seguridad/owasp.md`. |
| 7 | Archivar en `historico/` los planes ejecutados y los reportes de inventario, y **retirar de la raíz `ARQUITECTURA.md` y `CONTRATO_API.md`**, cuyo contenido ya vive aquí. Mientras sigan en la raíz hay dos copias de las mismas reglas: **mandan las de `docs/`**. |

## Cómo se mantiene este índice

- Un documento nuevo entra aquí en el mismo cambio que lo crea, con su tipo y su estado.
- Cuando un documento pasa de plan a normativo, se actualiza su fila y su línea 2.
- Los documentos sin verificar más de un mes llevan la fecha de verificación del
  encabezado sin actualizar, y eso se ve en la revisión.
