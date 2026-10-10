# ADR-0005 — Un error esperado se devuelve, no se lanza
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El legacy devolvía HTTP 200 en errores de negocio y 500 con cuerpo `{}`
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`). Un
resultado de negocio modelado como excepción lleva al `try/catch` por todas partes y, con
él, a los errores silenciados: «la cantidad excede la factura» no es un fallo del programa,
es un resultado esperado (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/common/Either.kt`).

`kotlin.Result` no sirve para ese caso porque obliga a que el error sea un `Throwable`, y un
resultado de negocio esperado no lo es (`Either.kt`). Hacía falta un tipo de resultado propio
([api-ktor.md](../arquitectura/api-ktor.md) §1.11).

## Decisión

- Un error esperado de negocio se devuelve. La operación devuelve `Either<DomainError, A>`,
  con el alias `DomainResult<A>` para el caso del dominio (`Either.kt`).
- `DomainError` es un catálogo cerrado `sealed`, con `code` estable para el cliente y
  `message` legible. Un error nuevo se añade al catálogo antes de usarlo (`DomainError.kt`,
  [errores.md](../api/errores.md)).
- La firma declara los fallos: `suspend fun execute(...): Either<DomainError, ...>`. Una
  función que devuelve `Unit` esconde en el tipo lo que puede fallar ([api-ktor.md](../arquitectura/api-ktor.md) §1.11).
- El `try/catch` se encapsula en el borde —el repositorio o el cliente externo— y allí se
  traduce a un fallo tipado. Un `SQLException` no sube a la aplicación
  ([api-ktor.md](../arquitectura/api-ktor.md) §1.11).
- Un fallo de programación —estado imposible, `when` sin cubrir— sí se lanza: es un bug y
  tiene que verse ([api-ktor.md](../arquitectura/api-ktor.md) §1.11).

## Consecuencias

Un `when` exhaustivo sobre `DomainError` no compila si aparece un caso nuevo sin tratar, así
que el manejo no se puede olvidar (`DomainError.kt`). Cada error tiene un código estable que
se mapea a HTTP sin perder información, y el cliente decide con el `code`, no con el mensaje
(`DomainError.kt`, `backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`).

Se paga que el tipo se contagia: un caso de uso que devuelve `Either` obliga a sus llamadores
a tratarlo, y el `try/catch` del borde no se puede olvidar.

Estado real, que conviene no maquillar: la regla se cumple en el dominio y solo en parte en
la API. Las rutas y los servicios todavía lanzan excepciones HTTP —por ejemplo
`backend/src/main/kotlin/dev/axzy/stock/backend/order/OrderService.kt`—, y el tipo de fallo
de almacenamiento propio del backend (`DomainFailure`) todavía no existe, así que un fallo
de base no tiene un sitio tipado donde vivir ([api-ktor.md](../arquitectura/api-ktor.md) §1.11, §1.18 y [lo-no-verificado.md](../guia/lo-no-verificado.md)). Mientras
convivan el retorno y el lanzamiento, la firma de un caso de uso no dice toda la verdad.

Reglas operativas que se derivan:

- No se lanza una excepción para un error de negocio.
- Un error de negocio no se responde con `500`: un `500` dice que el servidor falló cuando el
  servidor está bien, y un cliente que reintenta ante un `500` reintenta para siempre
  ([errores.md](../api/errores.md)).
- Los códigos de error salen del catálogo cerrado y no se inventan ([errores.md](../api/errores.md)).

## Alternativas consideradas

- **Excepciones con un manejador global (`StatusPages`).** La ruta no declara lo que puede
  fallar, y el manejo queda fuera de la vista de quien la escribe ([api-ktor.md](../arquitectura/api-ktor.md) §1.11).
- **`kotlin.Result`.** Obliga a que el error sea un `Throwable` (`Either.kt`).
- **`arrow-kt`.** Añade una dependencia a un dominio que debe ser Kotlin puro
  (`Either.kt`, `stock-domain/build.gradle.kts`).

## Referencias

- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/common/Either.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/Adjustment.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/order/OrderService.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`
- [api-ktor.md](../arquitectura/api-ktor.md) §1.11, §1.18 y [errores.md](../api/errores.md)
