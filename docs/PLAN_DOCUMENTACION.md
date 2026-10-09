# Plan de documentación — STOCK (Almacén Integral)

> Documento de trabajo. Propone cómo reordenar la documentación de STOCK tomando como
> referencia la de CYC (`~/DEV/CYC/docs`). Se elimina o se archiva cuando se ejecute.

---

## 1. Diagnóstico: por qué está mal iniciada

| # | Problema | Evidencia |
|---|---|---|
| 1 | **No hay punto de entrada.** No existe `README.md` en la raíz ni índice de documentación. | `ls` de la raíz; `docs/` solo tiene `adr/` vacía. |
| 2 | **Documentos de tipos distintos mezclados en la raíz.** Normativo, plan, inventario legacy y contrato conviven sin señal de cuál manda. | `ARQUITECTURA.md` dice ser normativo; `PLAN_*.md` son propuestas; `REPORTE_ARQUITECTURA_FSD_RESPONSIVA_WEB.md` documenta **otro proyecto** (`RESPONSIVA/web`). |
| 3 | **Volumen concentrado.** ~9.900 líneas en 8 archivos grandes. CYC tiene 52 archivos de ≤170 líneas por guía. | `PLAN_ALMACEN_INTEGRAL.md` (2.335) y `REPORTE_…` (2.296). |
| 4 | **Narrativa de conversación dentro del documento.** Frases como «el usuario preguntó», «me equivoqué dos veces», «decisión confirmada contigo». Eso es historial, no especificación. | `ARQUITECTURA.md` (cabecera), `PLAN_ALMACEN_INTEGRAL.md` §0 y §2.7. |
| 5 | **Documentación desfasada del código.** Dice que no hay scaffold ni tests, y el repo ya tiene backend Ktor, módulo `stock-domain`, app KMP y reportes de pruebas en `build/`. | `PLAN_ALMACEN_INTEGRAL.md` §0 («no incluye scaffold de código», «ninguno de los tres repos tiene un solo test»). |
| 6 | **Decisiones enterradas en el texto.** Existen como «B4», «B7», «B8», «R-73», «A-001» sin registro central. `docs/adr/` está vacía. | `grep` de `decisión` en `PLAN_ALMACEN_INTEGRAL.md`. |
| 7 | **Contenido repetido.** La arquitectura de la API aparece en `ARQUITECTURA.md` §1 y en `PLAN_ALMACEN_INTEGRAL.md` §3.2. Los permisos aparecen en tres documentos. | Revisión manual de índices de secciones. |
| 8 | **Cambios sin confirmar.** Decenas de archivos de documentación y código modificados sin commit. Mover archivos ahora perdería historial si no se hace con cuidado. | `git status` al inicio de la sesión. |

**Lo que sí está bien y se conserva:** el contenido técnico (contrato de API, reglas de
inventario, modelo de datos, estrategia de pruebas, inventario del legacy). El problema es
la forma, no la información.

---

## 2. Qué toma de CYC y qué no

**Se adopta:**

- Índice maestro `docs/README.md` con tabla por área y una sección «¿Primera vez aquí?».
- Carpetas por tema: `guia/`, `arquitectura/`, `modelo-datos/`, `api/`, `seguridad/`,
  `modulos/`, `operacion/`, `pruebas/`, `plantillas/`.
- Una carpeta por módulo de negocio con plantilla única (`plantillas/plantilla-modulo.md`).
- Tabla de estado por módulo (Documentado / Terminado / Pendiente).
- Registro de decisiones numerado (`docs/adr/`), referenciado desde los documentos.
- Documentos de guía cortos (≤170 líneas).

**No se adopta:**

- El sufijo de cliente o de proyecto en los nombres. STOCK es un solo repo.
- Pantallas y módulos de un ERP escolar. Los módulos de STOCK son los del almacén.

---

## 3. Estructura objetivo

```
README.md                      # raíz: qué es, cómo arrancar (enlaza a ARRANQUE.md) y a docs/
ARRANQUE.md                    # se queda en la raíz: quickstart operativo
docs/
├── README.md                  # índice maestro y estado por área
├── guia/
│   ├── vision-alcance.md      # problema, alcance, fuera de alcance
│   ├── glosario.md            # ubicación, bodega, kardex, traslado, surtido…
│   ├── convenciones.md        # reglas transversales (idioma, comentarios, errores)
│   └── roadmap.md             # fases y dependencias
├── arquitectura/
│   ├── general.md             # componentes y flujos entre API, web, app y dominio
│   ├── stack.md               # Ktor 3, Exposed, PostgreSQL 16, React 19, KMP, Compose
│   ├── dominio-kmp.md         # stock-domain: reglas puras, sin dependencias
│   ├── api-ktor.md            # capas por feature (de ARQUITECTURA.md §1)
│   ├── web-fsd.md             # Feature-Sliced Design y límites de importación
│   ├── axzy-ui-system.md      # uso de @axzydev/axzy_ui_system
│   └── app-kmp.md             # mobile-app: slices + core compartido
├── modelo-datos/
│   ├── entidad-relacion.md
│   └── diccionario-datos.md   # generado desde las migraciones Flyway
├── api/
│   ├── contrato.md            # antes CONTRATO_API.md, dividido por recurso
│   ├── convenciones.md        # rutas, paginación, idempotencia, fechas
│   ├── autenticacion.md
│   └── errores.md             # envelope y catálogo de códigos
├── seguridad/
│   ├── owasp.md
│   ├── roles-permisos.md      # de PLAN_ROLES_PERMISOS_POLITICAS.md (solo estado final)
│   └── auditoria.md           # de PLAN_AUDITORIA.md (solo estado final)
├── modulos/                   # un README por capacidad de negocio
│   ├── README.md              # índice y estado
│   ├── M01-catalogo/          # productos, ubicaciones, bodegas, importación
│   ├── M02-existencias/       # stock por ubicación, kardex, ajustes
│   ├── M03-recepcion/         # facturas, captura física, discrepancias
│   ├── M04-surtido/           # órdenes, empaquetado
│   ├── M05-traslados/         # movimientos entre ubicaciones y bodegas
│   ├── M06-etiquetas/         # impresión y etiquetas de malla / libres
│   ├── M07-usuarios-accesos/  # roles, ABAC, sesiones
│   ├── M08-auditoria/         # bitácora y trazabilidad
│   └── M09-integracion-legacy/# kardo y sincronización (decisión pendiente)
├── migracion/
│   ├── legacy-inventario.md   # BASE/ (backend Node, frontend React 18, app Expo)
│   ├── mapa-legacy-nuevo.md   # endpoint o pantalla antigua → nuevo módulo
│   ├── catalogo-excel.md      # perfil del inventario real (§3.5 del plan)
│   └── cutover.md             # pasos de corte y reversión
├── operacion/
│   ├── entornos.md
│   ├── despliegue.md
│   └── respaldos.md
├── pruebas/
│   └── estrategia-pruebas.md  # niveles, invariantes property-based, cobertura
├── adr/                       # ADR-0001… una decisión por archivo
├── plantillas/
│   └── plantilla-modulo.md
├── referencias/
│   └── fsd-responsiva.md      # de REPORTE_ARQUITECTURA_FSD_RESPONSIVA_WEB.md (otro proyecto)
└── historico/                 # planes ya ejecutados, archivados (no normativos)
```

Nota: `M01…M09` son las capacidades de STOCK, no los módulos de CYC. La numeración se
usa solo para enlazar y se fija en la fase 3.

---

## 4. Reglas de escritura

1. **Cada documento declara su tipo** en la línea 2: `Normativo`, `Plan`, `Referencia` o
   `Histórico`. Un documento normativo gana sobre cualquier plan.
2. **Cada regla vive en un solo lugar.** Los demás documentos enlazan, no copian.
3. **Estado, no historial.** Nada de «el usuario preguntó» ni «me equivoqué». Si una
   decisión cambió, va a un ADR nuevo que reemplaza al anterior.
4. **Verificable.** Cada afirmación técnica cita su archivo (`backend/build.gradle.kts`),
   o no entra.
5. **Fecha de última verificación** en el encabezado de los documentos de arquitectura y
   API. Un documento sin verificar más de un mes queda marcado.
6. **Sin afirmaciones de cantidad que caducan** (líneas, tests, número de pantallas) fuera
   de la tabla de estado.

---

## 5. Fases de ejecución

| Fase | Qué se hace | Salida verificable |
|---|---|---|
| **0. Congelar** | Commit de los cambios pendientes (o rama de documentación). Confirmar TypeScript para la web (ver §6). | `git status` limpio antes de mover archivos. |
| **1. Esqueleto** | Crear `README.md` raíz, `docs/README.md`, carpetas y `plantillas/plantilla-modulo.md`. Mover con `git mv` para conservar historial. | Todos los enlaces del índice resuelven a un archivo existente. |
| **2. Normativos** | Partir `ARQUITECTURA.md` y `CONTRATO_API.md` en los archivos de §3. Quitar la narrativa. Verificar cada regla contra el código. | Cada regla tiene ruta de archivo. Sin «usuario» ni «me equivoqué». |
| **3. Módulos** | Un `README.md` por `M01…M09` con la plantilla. Tabla de estado en `modulos/README.md`. | Cada módulo tiene: objetivo, reglas, API, permisos, pruebas, criterios de aceptación. |
| **4. Migración** | Escribir `migracion/` a partir de `MOBILE_APP_INVENTORY_REPORT.md`, `PLAN_ALMACEN_INTEGRAL.md` §1, §2.2-quater y §3.5. Crear `mapa-legacy-nuevo.md`. | Cada pantalla y endpoint legacy aparece en el mapa con su destino o su motivo de descarte. |
| **5. Decisiones** | Convertir B4, B7, B8, R-71, R-73, A-001 y similares en ADR. | `docs/adr/` con un archivo por decisión y estado (`Aceptada`, `Pendiente`). |
| **6. Operación y pruebas** | Partir `ARRANQUE.md` en `operacion/`; `pruebas/estrategia-pruebas.md` desde §3.6 del plan. | Comandos de arranque probados desde cero. |
| **7. Archivo** | Mover los `PLAN_*.md` ejecutados y los reportes de inventario a `historico/`. Eliminar duplicados. | La raíz solo tiene `README.md` y `ARRANQUE.md` (más lo que el build exige). |

**Criterio de terminado de todo el plan:** un lector nuevo llega al índice, encuentra cómo
arrancar, entiende qué módulos existen y su estado, y puede ver el mapa del legacy. Cada
enlace funciona y ningún documento normativo contradice al código.

---

## 6. Decisiones que necesito de ti

1. **¿La web en TypeScript o en JavaScript?** El repo ya usa TypeScript 5.7 en `web/`,
   y la documentación lo asume. Recomiendo TypeScript: el contrato de API se tipa en el
   cliente. Si dices JavaScript, la documentación lo refleja y se retira la mención de tipos.
2. **¿Habrá más de una bodega?** Afecta a `modelo-datos/` y a `M05-traslados`. El plan
   (§2.6) lo deja abierto. Si la respuesta es «no», se simplifica el modelo y el documento
   de traslados.
3. **¿Los códigos con sufijo `N` son el mismo producto?** Son 201 productos del Excel
   (§3.5). Hay que resolverlo antes de documentar la migración del catálogo.

Puedo ejecutar las fases 0 a 2 sin esperar estas respuestas. Las fases 3 a 5 necesitan
las respuestas 2 y 3 para no documentar algo que luego cambia.
