# ADR-0003 — Vertical slice en la app móvil
> **Vigente.** Se mantiene para la app KMP; ver [arquitectura/app-kmp.md](../arquitectura/app-kmp.md).
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

La app se compila como un build de Gradle propio, separado del monorepo, porque un
directorio no puede ser raíz de dos builds y meterlo con `include(":mobile-app")` deja el
proyecto sin raíz y rompe la sincronización del IDE (`mobile-app/settings.gradle.kts`,
`settings.gradle.kts`, [app-kmp.md](../arquitectura/app-kmp.md) §3.1).

La app consume el dominio como build incluido (`includeBuild("..")`), que es la misma
instancia y no una copia: las decisiones de negocio son idénticas en backend y móvil por
construcción (`mobile-app/settings.gradle.kts`).

Su arquitectura es vertical slice: la feature es la unidad de organización y tiene un
`core/` compartido. Su fuerza es la localidad —cambiar una funcionalidad toca una sola
carpeta— y es, a propósito, la arquitectura que la API abandonó en ADR-0001
([general.md](../arquitectura/general.md) y [app-kmp.md](../arquitectura/app-kmp.md) §3.1).

## Decisión

- `mobile-app/` es un build KMP autocontenido con dos módulos, `shared/` y `androidApp/`, y
  su propio `settings.gradle.kts` (`mobile-app/settings.gradle.kts`).
- El dominio no se duplica: se consume por `includeBuild("..")` con sustitución por
  coordenada (`mobile-app/settings.gradle.kts`).
- Dentro de `shared`, el código se parte en `core/` (compartido: `theme`, `ui`, `camera`,
  `nav`, `data`) y `feature/` (una carpeta por funcionalidad: `auth`, `home`, `inventory`,
  `invoices`, `labels`, `locations`, `orders`).
- Un cambio de funcionalidad toca una sola carpeta de `feature/`. Lo que usan dos features se
  promueve a `core/`; no se copia.
- Los targets JVM y Android están siempre. iOS es opt-in con `-PenableIosTargets=true`
  ([app-kmp.md](../arquitectura/app-kmp.md) §3.2).
- Los comandos del móvil se lanzan desde `mobile-app/`, no desde la raíz
  ([app-kmp.md](../arquitectura/app-kmp.md) §3.3).

## Consecuencias

Se gana localidad y un test que corre en JVM en segundos, sin emulador: es lo que valida el
código común de la app ([app-kmp.md](../arquitectura/app-kmp.md) §3.2).

Se paga que los comandos no se lancen desde la raíz, que iOS exija recordar el flag y que el
`.xcodeproj` no esté verificado en Xcode ([app-kmp.md](../arquitectura/app-kmp.md) §3.2 y [lo-no-verificado.md](../guia/lo-no-verificado.md)).

Reglas operativas que se derivan:

- Las reglas de negocio viven en `stock-domain`, no en `feature/`. Si una regla se escribe
  dos veces, una de las dos dejará de ser cierta.
- El dominio no se copia dentro de la app: se consume como build incluido.
- La vertical slice no tiene guardia automática. La disciplina es del revisor: una feature no
  importa de otra feature, y lo compartido se declara en `core/`.

## Alternativas consideradas

- **Incluir `mobile-app` como submódulo del monorepo.** Comprobado y descartado: el
  directorio deja de ser raíz de su build, IntelliJ no sincroniza y
  `./gradlew :mobile-app:shared:jvmTest` falla (`settings.gradle.kts`).
- **FSD en la app.** Las capas dentro de cada feature complican el montaje de Compose y no
  aportan la regla que sí importa en la web, que es la dirección entre pantallas
  ([general.md](../arquitectura/general.md)).
- **Copiar `stock-domain` dentro de la app.** Sería tener dos definiciones de la misma regla
  de negocio, que es justo lo que el módulo compartido evita
  (`mobile-app/settings.gradle.kts`).

## Referencias

- `mobile-app/settings.gradle.kts`
- `settings.gradle.kts`
- `mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/App.kt`
- `mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/core/`
- `mobile-app/shared/src/commonMain/kotlin/dev/axzy/stock/mobile/feature/`
- `stock-domain/build.gradle.kts`
- [general.md](../arquitectura/general.md) y [app-kmp.md](../arquitectura/app-kmp.md) §3.1, §3.2, §3.3
