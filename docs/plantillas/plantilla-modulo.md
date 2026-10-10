# M0X — Nombre de la capacidad
> **Normativo.** Un documento por módulo de negocio. Estado y fecha en la tabla de
> abajo. Última verificación: AAAA-MM-DD.

| | |
|---|---|
| **Código** | M0X |
| **Carpeta** | `docs/modulos/M0X-nombre/` |
| **Documentado** | No |
| **Terminado** | No |
| **Responsable** | — |

## 1. Objetivo

Qué problema de negocio resuelve este módulo, en dos o tres frases. Sin historia ni
narración de conversaciones: qué hace hoy, no cómo se llegó hasta aquí.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Lo que el módulo hace | Lo que parece de este módulo y no lo es |

## 3. Reglas de negocio

Una regla por fila. **Cada regla cita el archivo donde vive**, y vive solo ahí: si
otro documento necesita la regla, la enlaza.

| # | Regla | Dónde vive |
|---|---|---|
| R1 | | `stock-domain/src/...` |

## 4. Modelo de datos

Tablas que usa o posee, y qué es de quién.

| Tabla | Migración | Qué guarda |
|---|---|---|
| | `V…__*.sql` | |

## 5. API

| Método y ruta | Qué hace | Permiso |
|---|---|---|
| | | |

El detalle de cada endpoint está en `docs/api/`, no aquí.

## 6. Permisos

Qué permisos gobiernan el módulo y con qué alcance (bodega, ubicación, propio).

## 7. Bitácora

Qué escribe este módulo en `audit_log` y en qué transacción. Si no escribe nada, dilo.

## 8. Pruebas

Qué se prueba y a qué nivel. Enlaza a `docs/pruebas/estrategia-pruebas.md` para los
niveles, no los repitas.

| Nivel | Archivo | Qué cubre |
|---|---|---|
| | | |

## 9. Criterios de aceptación

Lista verificable: qué tiene que ser cierto para dar el módulo por terminado.

- [ ]

Las **pantallas** del módulo y las **decisiones** que lo gobiernan no llevan sección propia:
las pantallas están en la tabla de [modulos/README.md](../modulos/README.md) y cada decisión
se enlaza a su ADR en la regla que la usa.
