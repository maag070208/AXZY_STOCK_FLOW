# Arquitectura general: API, app y web
> **Normativo.** Cómo se relacionan las tres partes de STOCK y qué se comparte entre ellas. Última revisión: 2026-10-09.

| Parte | Tecnología | Arquitectura | Unidad de organización | Fuerza |
|---|---|---|---|---|
| **API** | Kotlin + Spring Boot, PostgreSQL | Capas con dependencias hacia dentro | Capa, y dentro el concepto de negocio | La regla de negocio no depende de nada |
| **App** | Kotlin Multiplatform (Android, iOS) | Vertical slice con núcleo compartido | La funcionalidad de piso | Cambiar una pantalla toca una sola carpeta |
| **Web** | React + Vite + AXZY UI System | Feature-Sliced Design | La capa, y dentro la feature | Una página no depende de otra |
| **Dominio compartido** (si D6) | Kotlin Multiplatform sin framework | Reglas puras | El concepto de negocio | No depender de nadie |

Cada parte usa la arquitectura que resuelve su problema dominante:

- **API.** Las reglas de inventario deben cumplirse sin importar quién escribe. Por eso la API tiene
  capas con enforcement y las reglas del motor viven en la base ([api-spring.md](api-spring.md),
  [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md)).
- **App.** El operador de piso necesita que una pantalla nueva no rompa otra. Por eso cada
  funcionalidad es una carpeta completa ([app-kmp.md](app-kmp.md), [ADR-0003](../adr/0003-vertical-slice-en-la-app.md)).
- **Web.** La administración tiene estado complejo y muchas pantallas. Por eso las dependencias entre
  capas se imponen con reglas de importación ([web-fsd.md](web-fsd.md), [ADR-0002](../adr/0002-fsd-en-la-web.md)).

## Reglas que valen para las tres partes

1. **Nombres en inglés** en código, tablas, columnas y endpoints. Se escriben en español solo los
   comentarios, los mensajes de error, los nombres visibles y los códigos de error, que son parte del
   contrato ([ADR-0004](../adr/0004-nombres-en-ingles-comentarios-en-espanol.md)).
2. **Cada regla de negocio vive en un solo sitio.** Los flujos en [flujos/](../flujos/README.md) son
   la referencia; el código y las pruebas los implementan. Ninguna parte reimplementa una regla
   que ya está en la API o en el dominio compartido.
3. **La API es la fuente de verdad.** La app y la web no guardan un inventario propio: muestran lo que
   la API responde, y la app solo guarda caché para trabajar sin red si se decide en un ADR.
4. **Los errores de negocio son códigos estables** ([api/errores.md](../api/errores.md)). El texto
   visible cambia; el código no.
5. **Cada escritura queda en la bitácora** ([flujos/auditoria.md](../flujos/auditoria.md)).

## Grafo entre partes

```
                  dominio compartido  (KMP puro, si D6)
                    │              │
                    ↓              ↓
   web (React) ──HTTP──→ API (Spring) ──→ PostgreSQL
                    ↑              ↑
                    └── app (KMP)──┘
                     (HTTP, mismo contrato)
```

- La web y la app hablan con la API por el mismo contrato HTTP ([api/convenciones.md](../api/convenciones.md)).
- La API y la app comparten el dominio solo si D6 se aprueba. Si no, la API tiene su propia copia de
  las reglas y el documento de referencia es el de [flujos/](../flujos/README.md).

## Qué queda fuera de este documento

- Detalle de cada capa: [api-spring.md](api-spring.md), [app-kmp.md](app-kmp.md), [web-fsd.md](web-fsd.md).
- Versiones: [stack.md](stack.md).
- Entorno y migraciones: [infraestructura.md](infraestructura.md).
- Plan y fases: [plan/PLAN-REHACER.md](../plan/PLAN-REHACER.md).
