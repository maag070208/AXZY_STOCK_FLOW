# Documentación de Almacén Integral
> **Normativo.** Este índice y la convención de tipos de abajo mandan sobre cómo se
> escribe el resto de la documentación. Última verificación: 2026-10-09.

## ¿Primera vez aquí?

1. [README.md](../README.md) — qué es el proyecto y cómo arrancarlo.
2. [arquitectura/general.md](arquitectura/general.md) — las cuatro partes y por qué cada una usa una arquitectura distinta.
3. [guia/vision-alcance.md](guia/vision-alcance.md) — qué construye STOCK y qué queda fuera.
4. [modulos/README.md](modulos/README.md) — las capacidades de negocio y su estado real.

## Convención de tipos de documento

**Cada documento declara su tipo en la línea 2**, con una de estas cuatro palabras:

| Tipo | Qué significa | Manda sobre |
|---|---|---|
| **Normativo** | Cómo se hacen las cosas aquí. Si el código lo contradice, uno de los dos está mal. | Todo |
| **Plan** | Trabajo propuesto o en curso. No describe el estado actual. | Nada |
| **Referencia** | Material de consulta: contrato de API, diccionario de datos, mapa del legacy. | Nada |
| **Histórico** | Lo ya ejecutado o descartado. Se conserva por trazabilidad. | Nada |

Reglas que se verifican en cada documento nuevo:

1. **Un normativo gana sobre un plan.** Si un plan contradice un normativo, el plan está equivocado.
2. **Cada regla vive en un solo lugar.** Los demás documentos enlazan; no copian.
3. **Cada afirmación técnica cita su archivo de código**, o no entra en un normativo.

**Numeración heredada.** Al partir un documento, sus secciones conservan los números del
original —`api-ktor.md` §1.x, `web-fsd.md` §2.x, `api/convenciones.md` §1.x, `api/errores.md`
§1.3— para que las citas que ya existían sigan siendo trazables. Cada documento numera lo
suyo, así que **dos documentos pueden tener un §1.3 distinto**: el número solo es válido
dentro del documento al que acompaña el enlace.

## Estado por área

| Área | Carpeta | Qué contiene | Estado |
|---|---|---|---|
| Guía | [guia/](guia/) | Visión, glosario, convenciones, roadmap, lo no verificado | Hecho |
| Arquitectura | [arquitectura/](arquitectura/) | Las cuatro partes, el stack y las reglas de dependencia | Hecho |
| Modelo de datos | [modelo-datos/](modelo-datos/) | Entidad-relación y lista de tablas por migración | Hecho |
| API | [api/](api/) | Contrato por recurso, convenciones, autenticación y errores | Hecho |
| Seguridad | [seguridad/](seguridad/) | Roles y permisos, auditoría, hallazgos del legacy | Hecho |
| Módulos | [modulos/](modulos/) | Un README por capacidad de negocio (M01…M09) | Hecho |
| Migración | [migracion/](migracion/) | El legacy, el mapa de rutas, el catálogo del Excel y el corte | Hecho; corte pendiente |
| Operación | [operacion/](operacion/) | Entornos, despliegue y respaldos | Hecho; respaldos y despliegue pendientes |
| Pruebas | [pruebas/](pruebas/) | Estrategia y verificación | Hecho |
| Decisiones | [adr/](adr/) | Un ADR por decisión, numerados | 0001 a 0013 |
| Plantillas | [plantillas/](plantillas/) | La plantilla de módulo | Hecho |
| Referencias | [referencias/](referencias/) | Material de otro proyecto, para comparar | Hecho |
| Histórico | [historico/](historico/) | Planes y documentos anteriores, archivados | Hecho |

## Guía

| Documento | Tipo |
|---|---|
| [vision-alcance.md](guia/vision-alcance.md) | Normativo |
| [glosario.md](guia/glosario.md) | Referencia |
| [identidad-visual.md](guia/identidad-visual.md) | Referencia |
| [convenciones.md](guia/convenciones.md) | Normativo |
| [roadmap.md](guia/roadmap.md) | Plan |
| [lo-no-verificado.md](guia/lo-no-verificado.md) | Referencia |

## Arquitectura

| Documento | Tipo |
|---|---|
| [general.md](arquitectura/general.md) | Normativo |
| [api-ktor.md](arquitectura/api-ktor.md) | Normativo |
| [web-fsd.md](arquitectura/web-fsd.md) | Normativo |
| [app-kmp.md](arquitectura/app-kmp.md) | Normativo |
| [dominio-kmp.md](arquitectura/dominio-kmp.md) | Normativo |
| [stack.md](arquitectura/stack.md) | Referencia |
| [migracion-capas.md](arquitectura/migracion-capas.md) | Plan |

## Modelo de datos

| Documento | Tipo |
|---|---|
| [entidad-relacion.md](modelo-datos/entidad-relacion.md) | Referencia |
| [diccionario-datos.md](modelo-datos/diccionario-datos.md) | Referencia |

El diccionario lista las tablas y su migración. Las columnas están en las propias migraciones.

## API

| Documento | Tipo |
|---|---|
| [contrato.md](api/contrato.md) — índice, permisos por endpoint y lo que no existe | Referencia |
| [convenciones.md](api/convenciones.md) | Normativo |
| [autenticacion.md](api/autenticacion.md) | Referencia |
| [errores.md](api/errores.md) | Referencia |
| [catalogo.md](api/catalogo.md) | Referencia |
| [inventario.md](api/inventario.md) | Referencia |
| [recepcion.md](api/recepcion.md) | Referencia |
| [surtido.md](api/surtido.md) | Referencia |
| [traslados.md](api/traslados.md) | Referencia |
| [etiquetas.md](api/etiquetas.md) | Referencia |
| [reportes.md](api/reportes.md) | Referencia |
| [salud.md](api/salud.md) | Referencia |

## Seguridad y pruebas

| Documento | Tipo |
|---|---|
| [seguridad/roles-permisos.md](seguridad/roles-permisos.md) | Normativo |
| [seguridad/auditoria.md](seguridad/auditoria.md) | Normativo |
| [seguridad/owasp.md](seguridad/owasp.md) | Referencia |
| [pruebas/estrategia-pruebas.md](pruebas/estrategia-pruebas.md) | Normativo |
| [pruebas/verificacion.md](pruebas/verificacion.md) | Normativo |

## Módulos de negocio

| Módulo | Capacidad | Documento |
|---|---|---|
| M01 | Catálogo: productos, ubicaciones, bodegas | [M01](modulos/M01-catalogo/README.md) |
| M02 | Existencias, kardex, ajustes y reportes | [M02](modulos/M02-existencias/README.md) |
| M03 | Recepción: facturas y discrepancias | [M03](modulos/M03-recepcion/README.md) |
| M04 | Surtido: órdenes y empaquetado | [M04](modulos/M04-surtido/README.md) |
| M05 | Traslados | [M05](modulos/M05-traslados/README.md) |
| M06 | Etiquetas e impresión | [M06](modulos/M06-etiquetas/README.md) |
| M07 | Usuarios y accesos | [M07](modulos/M07-usuarios-accesos/README.md) |
| M08 | Auditoría | [M08](modulos/M08-auditoria/README.md) |
| M09 | Integración externa: ERP y `kardo` | [M09](modulos/M09-integracion-externa/README.md) |

El estado de cada módulo está en [modulos/README.md](modulos/README.md).

## Plantillas

| Plantilla | Para qué |
|---|---|
| [plantilla-modulo.md](plantillas/plantilla-modulo.md) | El esqueleto de un documento de módulo: objetivo, reglas, API, permisos, pantallas, pruebas y criterios de aceptación |

## Migración

| Documento | Tipo |
|---|---|
| [legacy-inventario.md](migracion/legacy-inventario.md) | Referencia |
| [mapa-legacy-nuevo.md](migracion/mapa-legacy-nuevo.md) | Referencia |
| [catalogo-excel.md](migracion/catalogo-excel.md) | Referencia |
| [cutover.md](migracion/cutover.md) | Plan |

## Operación

| Documento | Tipo |
|---|---|
| [entornos.md](operacion/entornos.md) | Normativo |
| [despliegue.md](operacion/despliegue.md) | Plan |
| [respaldos.md](operacion/respaldos.md) | Plan |

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
| [0008](adr/0008-discrepancias-y-merma.md) | Discrepancias y merma: una regla de ajuste, aprobada por factura | Aceptada |
| [0009](adr/0009-ubicacion-de-recepcion-real.md) | La ubicación de recepción es una ubicación real | Aceptada |
| [0010](adr/0010-rutas-de-servicio-con-api-key.md) | Rutas de sistema con clave de servicio, no con usuario | Aceptada |
| [0011](adr/0011-catalogo-fuente-maestra.md) | El catálogo lo maneja la API; el Excel solo siembra | Aceptada |
| [0012](adr/0012-conciliar-stock-heredado-con-conteo.md) | Reconciliar el stock heredado con conteo antes del corte | Aceptada; ejecución pendiente |
| [0013](adr/0013-ordenes-al-erp-con-reintentos.md) | Órdenes al ERP con reintentos e idempotencia | Aceptada; envío pendiente |

## Referencias e históricos

| Documento | Qué es |
|---|---|
| [referencias/fsd-responsiva.md](referencias/fsd-responsiva.md) | Plantilla de arquitectura de otro proyecto (`RESPONSIVA/web`), para comparar. No describe STOCK |
| [historico/ARQUITECTURA.md](historico/ARQUITECTURA.md) | Documento normativo original, partido en `arquitectura/` y `adr/`. Las citas `§` del código apuntan a este archivo |
| [historico/CONTRATO_API.md](historico/CONTRATO_API.md) | Contrato original, partido en `api/`. Manda [api/contrato.md](api/contrato.md) |
| [historico/PLAN_ALMACEN_INTEGRAL.md](historico/PLAN_ALMACEN_INTEGRAL.md) | Plan original del sistema y análisis del legacy. Sus tablas de progreso están desactualizadas |
| [historico/PLAN_REFACTOR_MEJORAS.md](historico/PLAN_REFACTOR_MEJORAS.md) | Propuestas de refactor (R-xx) y decisiones que las cierran |
| [historico/PLAN_ROLES_PERMISOS_POLITICAS.md](historico/PLAN_ROLES_PERMISOS_POLITICAS.md) | Diseño de roles y permisos, antes de [seguridad/roles-permisos.md](seguridad/roles-permisos.md) |
| [historico/PLAN_AUDITORIA.md](historico/PLAN_AUDITORIA.md) | Diseño de auditoría, antes de [seguridad/auditoria.md](seguridad/auditoria.md) |
| [historico/MOBILE_APP_INVENTORY_REPORT.md](historico/MOBILE_APP_INVENTORY_REPORT.md) | Inventario de la app del legacy |
| [historico/PLAN_DOCUMENTACION.md](historico/PLAN_DOCUMENTACION.md) | Plan con el que se reordenó esta documentación |

Cuando un documento histórico y uno normativo se contradicen, manda el normativo.

## Cómo se mantiene este índice

- Un documento nuevo entra aquí en el mismo cambio que lo crea, con su tipo y su estado.
- Cuando un documento pasa de plan a normativo, se actualiza su fila y su línea 2.
- Los documentos sin verificar más de un mes llevan la fecha de verificación del encabezado sin actualizar, y eso se ve en la revisión.
