# Arquitectura general: las cuatro partes del sistema

> **Normativo.** Cómo se organiza cada parte del monorepo, por qué cada una usa una
> arquitectura distinta y cómo se relacionan. Última verificación: 2026-10-09.

| Parte | Carpeta | Arquitectura | Unidad de organización | Fuerza |
|---|---|---|---|---|
| **API** | `backend/` | **Clean Architecture** (hexagonal) | la **feature**, y **dentro** la capa | la dirección de dependencias |
| **Web** | `web/` | **FSD** (Feature-Sliced Design) | la **capa**, y dentro la feature | la dirección de dependencias |
| **App** | `mobile-app/` | **Vertical slice en KMP** | la **feature**, con `core/` compartido | la localidad |
| **Dominio** | `stock-domain/` | **KMP puro** (hexagonal de facto) | el **concepto del negocio** | no depender de nadie |

**No son la misma arquitectura a propósito.** Cada una resuelve el problema dominante de
su parte:

- **Clean Architecture** (API) existe para que **la regla de negocio no dependa de nada**:
  `infrastructure → application → domain`, nunca al revés. Su precio es que **las capas
  van dentro de cada feature**, no en carpetas globales. Decisión:
  [ADR 0001](../adr/0001-clean-architecture-en-la-api.md). Detalle: [api-ktor.md](api-ktor.md).
- **FSD** (web) existe para interfaces con estado, donde importa **de qué puede depender
  qué**: una página no puede importar de otra página. Decisión:
  [ADR 0002](../adr/0002-fsd-en-la-web.md). Detalle: [web-fsd.md](web-fsd.md).
- **Vertical slice** (app) existe para que **cambiar una funcionalidad toque una sola
  carpeta**. Su fuerza es la localidad, no la dirección de dependencias. Decisión:
  [ADR 0003](../adr/0003-vertical-slice-en-la-app.md). Detalle: [app-kmp.md](app-kmp.md).
- **KMP puro** (dominio) existe para **no depender de nadie**, porque lo consumen las tres
  partes anteriores. Detalle: [dominio-kmp.md](dominio-kmp.md).

## Lo obligatorio en las cuatro partes

1. **Nombres en inglés** (variables, métodos, constantes, clases, tablas, columnas). Lo
   único que se escribe en español son los comentarios, los mensajes de error, los
   `displayName`, los valores de enum y los códigos de error, que son API pública. Regla y
   excepciones en [convenciones.md](../guia/convenciones.md). Decisión:
   [ADR 0004](../adr/0004-nombres-en-ingles-comentarios-en-espanol.md).
2. **Comentarios en español** que explican el **por qué**, no el qué
   ([convenciones.md](../guia/convenciones.md)).
3. **Cada regla de negocio vive en un solo sitio.** Los demás documentos enlazan; no copian.
4. **Cada afirmación técnica cita su archivo de código.** Una afirmación sin archivo no
   entra en un normativo.

## El grafo entre las partes

```
                 stock-domain  (KMP puro, sin dependencias)
                        │
        ┌───────────────┼───────────────┐
        │               │               │
    backend/         web/         mobile-app/
  (hexagonal)       (FSD)     (vertical, build propio)
        │               │               │
        └──── contrato de API (HTTP) ───┘
```

El grafo de dependencias reales entre módulos, leído de los `build.gradle.kts`, es
unidireccional y sin ciclos:

```
backend         ──→ stock-domain       (backend/build.gradle.kts)
mobile-app      ──→ stock-domain       (mobile-app/shared/build.gradle.kts, por coordenada)
stock-network   ──→ stock-domain       (stock-network/build.gradle.kts)
stock-database  ──→ stock-domain       (stock-database/build.gradle.kts)
stock-datastore    (sin dependencias internas)
```

`stock-network`, `stock-database` y `stock-datastore` están en el build y tienen
pruebas propias, pero **ningún módulo los consume todavía**: el móvil solo depende de
`stock-domain` y aún no habla con el backend, así que su cliente HTTP y su caché
offline están construidos y sin conectar. Está en la lista de lo no verificado
([lo-no-verificado.md](../guia/lo-no-verificado.md)).

**El contrato es la frontera.** La web y el móvil **no importan código del backend**:
hablan HTTP contra el contrato. Eso permite cambiarlo sin recompilar los clientes. El
contrato por recurso está en `docs/api/contrato.md`.

**El móvil consume el dominio como build incluido**, no como copia:
`mobile-app/settings.gradle.kts` declara `includeBuild("..")` y sustituye la coordenada
`dev.axzy.stock:stock-domain`. Las decisiones de negocio son idénticas en backend y móvil
**por construcción**, no por disciplina.

## Dónde vive cada regla

| Tema | Documento |
|---|---|
| API Ktor: capas, dependencias, concurrencia, errores, persistencia, seguridad, observabilidad, resiliencia | [api-ktor.md](api-ktor.md) |
| Web React: capas FSD y límites de importación | [web-fsd.md](web-fsd.md) |
| App KMP: build propio, targets, slices | [app-kmp.md](app-kmp.md) |
| Dominio KMP: pureza, contenido, reglas | [dominio-kmp.md](dominio-kmp.md) |
| Stack y versiones, con el archivo donde se declaran | [stack.md](stack.md) |
| Migración pendiente a capas de la API | [migracion-capas.md](migracion-capas.md) |
| Idioma, comentarios y estándares de código | [convenciones.md](../guia/convenciones.md) |
| Auditoría, roles y permisos | [auditoria.md](../seguridad/auditoria.md), [roles-permisos.md](../seguridad/roles-permisos.md) |
| Pruebas y verificación | [estrategia-pruebas.md](../pruebas/estrategia-pruebas.md), [verificacion.md](../pruebas/verificacion.md) |
| Lo que no está verificado | [lo-no-verificado.md](../guia/lo-no-verificado.md) |
