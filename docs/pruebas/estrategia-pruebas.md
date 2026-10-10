# Estrategia de pruebas: cuatro capas
> **Normativo.** Qué se prueba en cada nivel, para qué sirve cada uno y cómo se ejecuta. Última verificación: 2026-10-09.

Cuatro capas, y cada una caza lo que las otras no pueden. Cada capa existe porque algo se le
escapó a las de abajo.

| Capa | Dónde | Qué caza | Coste |
|---|---|---|---|
| **Unitaria pura** | `backend/src/test/kotlin/dev/axzy/stock/backend/catalog/CatalogValidationTest.kt` | reglas de validación y **códigos de error** | milisegundos, no toca la base |
| **Integración con PostgreSQL real** | `backend/src/test/kotlin/dev/axzy/stock/backend/infrastructure/outbound/persistence/inventory/MotorInventarioTest.kt`, `backend/src/test/kotlin/dev/axzy/stock/backend/infrastructure/outbound/persistence/inventory/DivergenceMapperTest.kt` | SQL, nombres de columna, funciones PL/pgSQL | segundos, necesita PostgreSQL |
| **E2E de API** | `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt` | token, RBAC, serialización, contrato HTTP | segundos, base dedicada |
| **E2E de navegador** | `web/e2e/flow.spec.ts` | que la pantalla pinte de verdad | segundos, backend + web + navegador |

### La regla que las ordena

> **Una capa de test no sustituye a otra.** Un test unitario no puede ver un nombre de
> columna; un e2e de navegador no puede decir **qué** campo del contrato está mal. Cuando algo
> falla, la capa que lo cazó dice dónde mirar.

### Los tres defectos que obligaron a escribir esto

**1. El mapper de la reconciliación leía columnas que el contrato publicaba.**
`DivergenceDto` se serializaba con nombres que eran los del `RETURNS TABLE` de una función
PL/pgSQL. Ningún test leía esa función: con el verificador por defecto, la ruta responde
`401` **antes** del mapper. Lo cubre ahora
`backend/src/test/kotlin/dev/axzy/stock/backend/infrastructure/outbound/persistence/inventory/DivergenceMapperTest.kt`, que
ejercita la función real y su nombre de columna.

**2. La misma falta de token daba `403` en unas rutas y `401` en otras.**
Cada feature traducía la denegación por su cuenta. Los tests de ruta usaban
`AllowAllPermissionChecker`, así que **nunca ejercitaban una denegación real**. Lo cazó
`ApiE2ETest`, que recorre varias rutas y exige el mismo estado en todas.

**3. La web se quedaba en blanco al recargar en una ruta autenticada.**
Dos fallos encadenados: `setSessionHooks` —el puente entre el cliente HTTP y la sesión— **no
se llamaba en ningún sitio**, así que ninguna petición llevaba el token; y
`useSessionBootstrap` cancelaba su propio resultado en el doble montaje de `StrictMode`,
dejando el estado en `loading` para siempre. Ninguna prueba de las capas anteriores podía
verlo: `tsc` compila (los ganchos tienen valores por defecto válidos), `eslint` no ve un
import que nunca se hizo, y los tests de API no abren un navegador. Lo cazó
`web/e2e/flow.spec.ts`, con un test dedicado a **recargar**, porque navegar dentro de la SPA
no lo mostraba.

### Cómo se ejecutan

```bash
# Unitarios y de integración (necesitan PostgreSQL: si no hay, se SALTAN y lo dicen)
./gradlew :backend:test :stock-domain:jvmTest

# E2E de API (incluido en lo anterior)
./gradlew :backend:test --tests "*ApiE2ETest*"

# E2E de navegador: levanta base dedicada, backend y web, y ejecuta Playwright
./scripts/e2e-web.sh
```

### Reglas de la casa para un test nuevo

- **Un test que se salta solo es un test que no vigila.** Si falta la base, se imprime el
  motivo; si falta una variable, el e2e **falla** diciendo qué ejecutar.
- **Un test que nunca ha fallado no demuestra que vigile nada**
  ([verificacion.md](verificacion.md) §2). Antes de dar por buena una guardia, se revierte el
  defecto y se comprueba que sale roja.
- **Nada de `sleep` fijos para esperar a un servicio.** El script del e2e
  (`scripts/e2e-web.sh`) espera a que **el administrador sembrado pueda iniciar sesión**: es
  la única señal que significa «puedo servir». Esperar al puerto, y hasta esperar a
  `schemaVersion`, se probó y **las dos mentían** — el servidor abre el puerto y responde el
  health antes de terminar de migrar.
- **Los specs comparten base, así que van en serie** (`workers: 1`,
  `web/playwright.config.ts`). En paralelo, el orden decide el resultado, que es la peor clase
  de fallo.
