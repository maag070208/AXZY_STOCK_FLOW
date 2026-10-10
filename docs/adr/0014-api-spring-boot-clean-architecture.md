# ADR-0014 — La API en Kotlin con Spring Boot, en capas, con inyección por constructor
> **Normativo.** Decisión aceptada. Reemplaza la parte de framework de [ADR-0001](0001-clean-architecture-en-la-api.md).

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | [ADR-0001](0001-clean-architecture-en-la-api.md) (solo el framework; la regla de dependencias sigue) |
| **Reemplazada por** | — |

## Contexto

La API anterior era Ktor con el cableado a mano (`by lazy`) y Koin en el classpath sin usarse.
Cada dependencia se resolvía con código propio, y eso dejó dos problemas: el ciclo de vida de
los objetos no era visible en un solo sitio, y la regla de dependencias se verificaba por
convención, no por el compilador. El equipo conoce Spring Boot y necesita un framework con
inyección de dependencias madura, documentación amplia y soporte de Docker sin fricción.

## Decisión

1. **Kotlin con Spring Boot** para la API, sobre JDK LTS (la versión exacta se fija en la fase 0,
   [stack.md](../arquitectura/stack.md)).
2. **Cuatro capas en módulos de Gradle separados**, y la dependencia solo puede apuntar hacia dentro:

   ```
   app (Spring Boot, cableado)  ──→  adapters/web, adapters/persistence
                                         │
                                         ↓
                                    application  (casos de uso, puertos)
                                         │
                                         ↓
                                      domain  (reglas puras, sin Spring)
   ```

   - `domain`: entidades, estados, errores tipados y reglas. **No depende de Spring ni de JDBC.**
   - `application`: casos de uso y **puertos** (interfaces). Depende solo de `domain`.
   - `adapters/persistence`: implementa los puertos de salida con PostgreSQL. Depende de `application`.
   - `adapters/web`: controladores HTTP que llaman a los casos de uso. Depende de `application`.
   - `app`: el único módulo que conoce Spring y **cablea** todo. Es el único que depende de todos.

3. **Inyección de dependencias por constructor.** Los casos de uso son clases Kotlin simples con
   sus puertos en el constructor. Spring solo se usa para instanciarlos en `app`, en una
   configuración por módulo. Un caso de uso se puede probar sin levantar Spring.
4. **Las transacciones son un puerto.** `application` define `TransactionRunner`; el adaptador de
   Spring (`TransactionTemplate`) lo implementa en `app`. Así la aplicación no depende de
   `@Transactional`.
5. **La autorización se decide en `application`**, no solo en el controlador. Un endpoint nuevo que
   olvide el controlador no puede saltarse la regla de permisos.

## Consecuencias

- La regla de dependencias la comprueba el compilador (los módulos no se ven entre sí en la
  dirección prohibida) y una prueba ArchUnit la repite en cada build.
- El cableado completo está en un solo lugar (`app`); es lo primero que se lee al buscar
  dónde se crea un objeto.
- Más módulos de Gradle que un proyecto de un solo módulo: se paga en configuración inicial.
- Spring queda fuera del dominio: si el framework cambia, cambia `app` y los adaptadores, no
  las reglas.

## Alternativas descartadas

- **Un solo módulo con paquetes y sin enforcement.** Las capas se degradan con el tiempo sin que
  nadie lo note; es el problema que la arquitectura anterior ya tuvo.
- **Anotaciones de Spring en el dominio** (`@Service`, `@Transactional`). Acopla las reglas al
  framework y hace que las pruebas del dominio necesiten contexto de Spring.
- **Koin en lugar de Spring.** Ya estaba en el classpath de la API anterior sin usarse; el equipo
  no tiene experiencia con él y Spring ofrece lo mismo con más documentación.
