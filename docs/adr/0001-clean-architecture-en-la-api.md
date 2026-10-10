# ADR-0001 — Clean Architecture por feature en la API
> **Reemplazo parcial.** El framework de esta decisión (Ktor) queda reemplazado por [ADR-0014](0014-api-spring-boot-clean-architecture.md). La regla de dependencias se mantiene.
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — (revierte la decisión previa de vertical slice en la API, que no tuvo ADR) |
| **Reemplazada por** | — |

## Contexto

La API se organizaba por feature con todo lo suyo dentro: rutas, servicio, DTOs,
repositorio y tablas en la misma carpeta, sin capas horizontales. La justificación era la
localidad: cambiar una funcionalidad tocaba una sola carpeta ([api-ktor.md](../historico/API-KTOR.md) §1.1).

Sin la regla de dependencias escrita, cada archivo decide por su cuenta: una ruta puede
importar el driver JDBC y un DTO puede importar Ktor. El diseño se degrada sin que nadie tome
la decisión. La lógica de negocio quedó además atrapada en las rutas y, sin puertos, la regla
no se puede probar sin PostgreSQL: la única prueba posible corre contra la base real
([api-ktor.md](../historico/API-KTOR.md) §1.1).

La reversión es solo de la API. La app móvil sigue en vertical slice (ADR-0003) y la web
sigue en FSD (ADR-0002). No es la misma arquitectura a propósito: cada parte la elige por
una razón distinta ([general.md](../arquitectura/general.md)).

## Decisión

La API usa Clean Architecture (hexagonal) organizada por feature, y dentro de la feature,
por capa.

- La regla de dependencias es `infrastructure → application → domain`. Nunca al revés y
  nunca un salto ([api-ktor.md](../historico/API-KTOR.md) §1.3).
- `domain/` usa el stdlib de Kotlin y `stock-domain`. `application/` no importa Ktor,
  Exposed, JDBC, HikariCP ni `kotlinx.serialization` en el caso de uso ni en los puertos.
  Los DTOs de la feature sí pueden usar `kotlinx.serialization`; el archivo del caso de uso
  nunca lleva `@Serializable` ([api-ktor.md](../historico/API-KTOR.md) §1.3).
- Las capas viven dentro de la feature, nunca en carpetas globales del tipo `services/`,
  `repositories/` o `routes/` ([api-ktor.md](../historico/API-KTOR.md) §1.2, §1.4).
- El ámbito transaccional lo define el caso de uso, no la ruta ([api-ktor.md](../historico/API-KTOR.md) §1.12).
- Las dependencias se inyectan por constructor, nunca con un `object` ([api-ktor.md](../historico/API-KTOR.md) §1.8, `backend/src/main/kotlin/dev/axzy/stock/backend/config/AppDependencies.kt`).
- La migración se hace por feature y compilando entre fases. `catalog/` ya está partido por
  recurso (`backend/src/main/kotlin/dev/axzy/stock/backend/catalog/`).

## Consecuencias

Se gana una dirección de dependencias explícita, una regla de negocio que se prueba sin base
de datos y un caso de uso que se puede exponer por otro canal sin arrastrar la representación
HTTP.

Se paga más estructura por feature: más archivos, más imports que mantener y una migración
que todavía no termina. El árbol objetivo de [api-ktor.md](../historico/API-KTOR.md) §1.2
**existe ya para `inventory/`, `receiving/` y `transfer/`**, y `catalog/` está partido por
recurso con sus servicios; el resto de features sigue `package-by-feature`
([migracion-capas.md](../historico/MIGRACION-CAPAS-KTOR.md) y
[lo-no-verificado.md](../historico/LO-NO-VERIFICADO-ANTERIOR.md)).

Reglas operativas que se derivan:

- El backend no puede tener carpetas globales de capas. Lo que usan dos o más features va a
  `config/`, `error/`, `security/` o `application/shared/`, no a un cajón por tipo de
  archivo ([api-ktor.md](../historico/API-KTOR.md) §1.4).
- Un archivo de caso de uso no se anota con `@Serializable`.
- La regla no tiene guardia automática todavía: se comprueba a mano con el `grep` de
  [api-ktor.md](../historico/API-KTOR.md) §1.3.

## Alternativas consideradas

- **Mantener vertical slice en la API.** La localidad es real, pero no hay dirección de
  dependencias escrita y la regla de negocio acaba repartida por los handlers
  ([api-ktor.md](../historico/API-KTOR.md) §1.1).
- **Volver a carpetas globales por capa.** El servicio en una carpeta y todas las rutas HTTP
  en otra obliga a saltar entre dos sitios para tocar una feature, y `routes/` acaba siendo un
  cajón de archivos sin relación entre sí
  ([api-ktor.md](../historico/API-KTOR.md) §1.2).
- **Migrar todo de una vez con un script.** Se intentó y se revirtió a propósito: el
  refactor rompió imports en masa y lo único verificable que quedó fue el destrozo. Se hace
  por feature y compilando entre fases ([api-ktor.md](../historico/API-KTOR.md) §1.2).

## Referencias

- [general.md](../arquitectura/general.md) y [api-ktor.md](../historico/API-KTOR.md) §1.1, §1.2, §1.3, §1.4, §1.8, §1.12, §1.18
- `backend/src/main/kotlin/dev/axzy/stock/backend/catalog/product/ProductRoutes.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/catalog/product/ProductRepository.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/config/AppDependencies.kt`
- `backend/build.gradle.kts`
- `stock-domain/build.gradle.kts`
