# Flujos de StockFlow
> **Normativo.** Los flujos que debe cumplir cada parte del sistema. Son la referencia para escribir
> las pruebas de aceptación y el código. Última revisión: 2026-10-09.

## Índice

| Flujo | Qué cubre | Módulo |
|---|---|---|
| [autenticacion-y-permisos.md](autenticacion-y-permisos.md) | Login, sesión, cambio de contraseña, decisión de acceso | Identidad |
| [catalogo.md](catalogo.md) | Productos, ubicaciones, bodegas, importación del Excel | Catálogo |
| [existencias-y-ajustes.md](existencias-y-ajustes.md) | Movimientos, ajustes, ubicar, mover, kardex, total por producto | Existencias |
| [recepcion.md](recepcion.md) | Factura, captura física, discrepancias, aprobación, ubicación | Recepción |
| [surtido-y-erp.md](surtido-y-erp.md) | Orden, empaque, faltantes, confirmación, exportación al ERP | Surtido |
| [traslados.md](traslados.md) | Traslado entre ubicaciones y entre bodegas, tránsito | Traslados |
| [etiquetas.md](etiquetas.md) | Plantillas, cola de impresión, confirmación, reimpresión | Etiquetas |
| [auditoria.md](auditoria.md) | Bitácora de escrituras, inmutabilidad, consulta | Auditoría |

## Cómo se lee un flujo

Cada flujo tiene las mismas secciones:

1. **Objetivo y actores**: quién lo usa y para qué.
2. **Precondiciones**: lo que tiene que existir antes.
3. **Flujo**: el diagrama de secuencia de la API, con los pasos que se cumplen en orden.
4. **Estados**: las transiciones permitidas. Cualquier transición que no esté aquí se rechaza.
5. **Pruebas de aceptación**: escenarios en formato Dado / Cuando / Entonces. Son la lista mínima
   que tiene que pasar antes de dar el flujo por terminado.
6. **Errores**: los códigos que puede devolver, con su significado.
7. **Permisos**: qué permiso exige cada paso.
8. **Bitácora**: qué escribe en la auditoría.
9. **Reglas y decisiones**: enlaces a los ADR que las justifican.

## Convenciones de los diagramas

- Participantes: `Operador` (app o web), `API`, `Caso de uso` (dentro de la API), `Base` (PostgreSQL).
- Un paso dentro de `Base` que termina con `COMMIT` marca el final de una transacción.
- Las líneas `alt` y `opt` son las ramas que las pruebas deben cubrir.

## Qué no está en los flujos

- Detalle de cada endpoint (cuerpo, respuesta, campos): [api/](../api/convenciones.md).
- Cómo se implementa cada paso en código: [arquitectura/api-spring.md](../arquitectura/api-spring.md).
- Reglas que no son de un flujo concreto: [seguridad/](../seguridad/roles-permisos.md).

## Correspondencia con los módulos anteriores

Los módulos M01–M09 en [modulos/](../modulos/README.md) describieron el comportamiento del sistema
anterior con rutas de código de Ktor. Las reglas de negocio de esos módulos siguen vigentes y los
flujos de esta carpeta las reorganizan por proceso. Cuando un flujo y un módulo se contradicen,
manda el flujo y el módulo se corrige.
