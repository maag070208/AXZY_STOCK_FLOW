# Migración a capas: el plan por fases

> **Plan.** Trabajo propuesto y en curso; no describe el estado actual.
> Última verificación: 2026-10-09.

**Estado: la partición de `catalog/` está hecha; las capas `domain`/`application`/
`infrastructure`, no.** El objetivo es la estructura de [api-ktor.md](api-ktor.md) §1.2.

> **Regla que no se negocia: la migración no cambia comportamiento.** Es un movimiento de
> código: mismas rutas, mismas respuestas, mismos códigos de estado, mismos tests. Si una
> respuesta cambia o un test se tiene que reescribir para pasar, **la migración se salió de
> su carril** y hay que parar y separar el cambio.

## Lo que ya está hecho: `catalog/` partido por recurso

| Fichero | Qué es |
|---|---|
| `catalog/CatalogRoutes.kt` | solo el cableado: repositorios y orden de montaje |
| `catalog/product/ProductRoutes.kt` | `GET\|POST\|PUT\|DELETE /v1/products` |
| `catalog/location/LocationRoutes.kt` | `GET\|POST\|PUT\|DELETE /v1/locations` |
| `catalog/warehouse/WarehouseRoutes.kt` | `GET /v1/warehouses` |
| `catalog/CatalogValidation.kt` | los helpers de validación, `internal` |

(rutas relativas a `backend/src/main/kotlin/dev/axzy/stock/backend/`; el tamaño de cada
fichero se mide con `wc -l` si hace falta, no se fija aquí.)

**Cero cambios de comportamiento:** mismas rutas, mismas respuestas. Se comprueba con los
comandos de [estrategia-pruebas.md](../pruebas/estrategia-pruebas.md), con 0 fallos y 0
saltados. El único test tocado es la guardia de visibilidad de `RoutesWiringTest`, y **es un
cambio de criterio, no un ajuste para que pase**.

**Éste es el patrón que hay que repetir** en `inventory/`, `receiving/`, `identity/`,
`audit/`, `health/` y el resto de features de la raíz: **un archivo por recurso, y el de
rutas se queda solo con el cableado.** Es un movimiento mecánico, compila entre pasos y no
toca SQL ni reglas.

### Tres cosas que cuestan una iteración cada una

1. **`private` de nivel superior en Kotlin es privado del fichero.** Al partir el archivo,
   los helpers dejan de ser accesibles desde los nuevos ficheros: hay que pasarlos a
   `internal` **en su propio archivo** (`CatalogValidation.kt`). No es un descuido de
   visibilidad: es la consecuencia obligada de partir.
2. **Las funciones de rutas también tienen que ser `internal`, no `private`.** Las llama
   `catalogRoutes()` **desde otro fichero**, así que `private` no compila. La visibilidad
   mínima que funciona es `internal`, y es la correcta: dentro del módulo, fuera de la API
   pública. La guardia de `RoutesWiringTest` excluye `private` **e `internal`**, porque las
   dos son implementación interna que **no** debe montar `Application.kt`. Si se montaran
   allí **además** de `catalogRoutes()`, las rutas quedarían registradas **dos veces**.
3. **Al partir el archivo se copian los imports de la cabecera a cada trozo.** Funciona (los
   que no se usan son un warning, no un error), **pero los imports internos que hacen falta
   se pierden si se filtraron por prefijo**. Ver la trampa siguiente.

## La trampa que costó la reversión completa, y la lección

Migrar **todo el backend a la vez** con un script que mueve ficheros y reescribe imports por
**prefijo de paquete** (`dev.axzy.stock.backend.catalog.*`) no funciona: produce errores de
compilación en cascada y obliga a revertir.

**La causa, y es reutilizable:** un script que reescribe «todos los imports que empiecen por
X» acierta con los que apuntan a la ruta vieja y **destruye los otros dos casos** —los que
apuntan a una ruta nueva equivocada y los que se quedan huérfanos—, porque **no sabe dónde
vive cada símbolo**. Y al no saberlo, borra imports que hacían falta **sin decirlo**.

**La regla que sale de ahí:**

> **Un refactor de paquetes se hace por feature y compilando entre fases.** Y para reparar
> imports, **la única fuente fiable es el compilador**, no un mapa de prefijos: se compila,
> se leen los `Unresolved reference 'X'` con su fichero, y se resuelve `X` contra el
> inventario real de declaraciones.

**Dos sub-trampas del mismo tipo:**

- **Un miembro de clase no es un símbolo importable.** `val size: Int` dentro de
  `data class Page` no se puede importar. Cuando el compilador dice `Unresolved reference
  'size'` sobre `page.size`, el problema real es que **falta el import de `Page`**. Tratarlo
  como símbolo genera imports inventados que rompen el fichero entero y **esconden el error
  de verdad**.
- **El nombre de una función de extensión es el identificador anterior al paréntesis.** En
  `fun <T, R> Page<T>.aResponse(...)` el nombre es `aResponse`, no `Page`. Un detector que se
  quede con el primer identificador atribuye `aResponse` al fichero equivocado y **borra un
  import que hacía falta**; el síntoma es el peor posible: el fichero compila y la llamada no
  resuelve.

## El punto de partida de la fase pendiente

`catalog/` mezclaba cuatro cosas distintas y sigue siendo el ejemplo del problema de fondo:
**`CatalogRoutes.kt` reunía tres recursos en funciones `private fun Route.*` y no hay
`CatalogService`**. Las rutas hablan con **cuatro repositorios** directamente, así que **no
hay dónde poner una regla de negocio que afecte a más de uno** y la validación acaba
repartida por los handlers.

**Partir un archivo no reduce líneas: reduce el alcance de lo que hay que leer para
tocarlo.** Cada fichero nuevo lleva su `package`, sus imports y la explicación de por qué
existe, y `CatalogValidation.kt` reúne los helpers que antes estaban repartidos por el
monolito.

**Y hay dos cosas que no son del catálogo:** la **importación de Excel** (un proceso por
lotes, con su ciclo de vida y sus errores; no comparte nada con un `GET /v1/products`) y
`Pagination.kt` / `CatalogTables.kt`, que **los usa el resto del backend** y están en
`catalog/` por accidente histórico.

## Las fases

El orden va **de dentro hacia fuera y de lo compartido a lo particular**, porque cada fase
tiene que dejar el build compilando.

**Fase 0 — Congelar el contrato.** Antes de mover nada: el contrato de API y los tests de
rutas en verde. **Si el contrato y la ruta no coinciden hoy, la migración convierte la
discrepancia en un bug importado** y ya no se sabrá de dónde vino. La corrección de las
columnas de reconciliación con la migración V8 es la lección.

**Fase 1 — Lo compartido sale de `catalog/`. Pendiente, y es la de más valor por línea
movida.** `Pagination.kt` y `CatalogTables.kt` salen **los primeros** porque los usa todo el
backend: `Page`, `PageRequest` y los resultados de consulta van a `application/shared/`, y
las tablas Exposed a `infrastructure/outbound/persistence/tables/`. **Hoy `identity/`,
`audit/` e `inventory/` importan `Pagination` del catálogo**, que es acoplamiento accidental
entre features: un módulo depende de otro por un detalle de paginación.

**Fase 2 — `catalog/`, recurso a recurso. Mitad hecha** (la partición, arriba). Falta la otra
mitad: crear `ProductService`/`LocationService`/`WarehouseService` y que las rutas deleguen.
Es lo que llena el hueco de «no hay dónde poner una regla que afecte a más de un recurso».

**Fase 3 — `inventory/` y `receiving/`.** Son los que llevan el motor PL/pgSQL y su propia
transacción ([api-ktor.md](api-ktor.md) §1.12). **Se mueven tal cual.** El patrón
`dataSource.connection.use { autoCommit = false; ... commit() }` **no se reescribe al
migrar**: formalizarlo es otra tarea. Si se «aprovecha» para cambiarlo, **se pierde la
capacidad de atribuir un fallo** — no se sabrá si lo rompió el movimiento o el cambio.

**Fase 4 — Transversales.** `identity/`, `audit/`, `docs/`, `health/`, `tools/`, y el
`domain/` propio del backend (`domain/model/` y `domain/error/DomainFailure`), que **hoy no
existe**; el catálogo de errores vive en `stock-domain`
([lo-no-verificado.md](../guia/lo-no-verificado.md)).

**Fase 5 — `Application.kt` y el composition root.** Se cierra la composición
([api-ktor.md](api-ktor.md) §1.8) y se comprueba que **no queda ningún directorio de feature
en la raíz del paquete**.

**Fase 6 — La guardia.** Una tarea de Gradle que **falle** si `domain/` o `application/`
importan Ktor, Exposed, JDBC o HikariCP —y, en el caso de uso, `kotlinx.serialization`—
([api-ktor.md](api-ktor.md) §1.3), al estilo de `verifyDomainPurity` y
`forbidJvmMainSourceSet` de `stock-domain/build.gradle.kts`. **Sin guardia, la regla se
degrada sola.**

## Trampas que siguen siendo ciertas

- **Los helpers de validación eran `private` de nivel superior** (`page`, `routeId`,
  `productCode`, `textRequired`, `locationPart`, `parseLocationKind`…). Ya están en
  `CatalogValidation.kt` con `internal`, que es donde tienen que estar.
- **`requirePermission` era `private`** y lo usan los tres recursos. Mismo tratamiento.
- **Cortar por número de línea no funciona**: un corte por rango se lleva funciones enteras
  (overloads duplicados) o parte un helper por la mitad (error de sintaxis). **Hay que cortar
  por el cierre real de cada función.**
- **Los imports de la cabecera.** Cada archivo nuevo necesita los suyos; copiarlos todos deja
  imports sin usar (warning, no error), **pero los internos que hacen falta se pierden si se
  filtraron por prefijo**.

## Cómo se sabe que la migración terminó

1. **Cero directorios de feature** en la raíz de
   `backend/src/main/kotlin/dev/axzy/stock/backend/`.
2. El `grep` de [api-ktor.md](api-ktor.md) §1.3 sobre `domain/` y `application/` devuelve
   **vacío**.
3. La guardia de la fase 6 **existe y falla cuando se la provoca a propósito**
   ([verificacion.md](../pruebas/verificacion.md)): se mete un `import io.ktor` en
   `application/` y la tarea tiene que romper. **Una guardia que nunca ha fallado no
   demuestra que vigile nada.**
4. Los tests de todos los niveles pasan, **0 fallos, 0 saltados**, y **ninguno reescrito**
   para que pase.
