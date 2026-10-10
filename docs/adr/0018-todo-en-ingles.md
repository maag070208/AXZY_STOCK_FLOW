# ADR-0018 — Todo en inglés: código, interfaz, datos, mensajes y documentación
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-10.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-10 |
| **Reemplaza a** | [ADR-0004](0004-nombres-en-ingles-comentarios-en-espanol.md) (comentarios y documentación en español) y [ADR-0017](0017-identificadores-y-datos-en-ingles.md) (excepciones pendientes) |
| **Reemplazada por** | — |

## Contexto

ADR-0004 dejó en español los comentarios, la documentación y los mensajes de error que lee el
operador. ADR-0017 pasó a inglés los códigos, las claves y los datos. Quedaban en español la
interfaz, los mensajes de la API, los nombres de las pruebas, las funciones SQL del motor y los
comentarios. El dueño del proyecto pidió que todo lo que sea español pase a inglés, con i18n para
la interfaz.

## Decisión

Todo lo que el proyecto escribe va en inglés:

- **Código**: identificadores, comentarios, nombres de pruebas y de archivos.
- **Interfaz web**: todo el texto visible sale de i18next (`src/shared/i18n/locales/en.json`). Los
  errores de la API se traducen por código, nunca se muestra el mensaje del servidor.
- **Datos y contrato**: claves de rol, códigos de bodega y ubicación (ADR-0017), nombres de permisos
  y roles visibles.
- **Mensajes de la API**: en inglés. Lo que el operador lee en un log también va en inglés.
- **Funciones SQL**: se renombran con una migración nueva; los cuerpos que se llaman entre sí se
  reescriben en la misma migración.
- **Documentación** (`docs/`, `ARRANQUE.md`, `README.md`): en inglés.

**Excepciones que se quedan**, porque son datos y no texto: la unidad de medida `PZA` del catálogo
legacy y la letra `Ñ` del alfabeto de códigos de producto.

## Estado de la migración

- Verificado: backend completo (`./gradlew verify`) y suite de navegador (30/30).
- Pendiente: nombres de pruebas, funciones SQL (migración `V17`), comentarios y documentación.

## Consecuencias

- Una documentación en inglés cambia cada enlace y cada tabla de `docs/`; se hace en una pasada
  con verificación de enlaces, no archivo por archivo.
