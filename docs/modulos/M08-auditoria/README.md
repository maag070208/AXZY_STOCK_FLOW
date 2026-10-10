# M08 — Auditoría: bitácora y trazabilidad
> **Normativo.** Última verificación: 2026-10-10 (M08 cerrado).

| | |
|---|---|
| **Código** | M08 |
| **Carpeta** | `docs/modulos/M08-auditoria/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí |
| **Web** | `AuditPage` |
| **App** | No hay pantalla |

## 1. Objetivo

Responder quién hizo qué, cuándo y por qué, sobre cualquier escritura del sistema.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Registro de cada escritura, con estado anterior y nuevo | Métricas técnicas (no son auditoría) |
| Consulta de la bitácora | Exportación a sistemas externos |
| Inmutabilidad por disparadores | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Toda escritura pasa por la bitácora | [seguridad/auditoria.md](../../seguridad/auditoria.md) |
| R2 | La bitácora no admite `UPDATE` ni `DELETE` | Disparadores en `V2__audit_and_traceability.sql` |
| R3 | La cadena de integridad se puede verificar | `V7__audit_write_and_verify.sql` |
| R4 | Retención por nivel: crítico permanente, relevante 3 años, acceso 1 año, técnico 30 días | [seguridad/auditoria.md](../../seguridad/auditoria.md) |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `audit_log`, `audit_log_default` | `V2` | Bitácora y su partición por defecto |
| `access_counter` | `V2` | Accesos agregados (nivel 3) |
| `config_versions` | `V2` | Versiones de la configuración de acceso |

## 5. API

Contrato en [api/auditoria.md](../../api/auditoria.md): `GET /v1/audit/log`, con filtros
por entidad, actor, acción, nivel, resultado, canal y rango de fechas de negocio, y
paginación con el total calculado con el mismo filtro. No hay `PUT` ni `DELETE`, a
propósito: la tabla es inmutable por disparadores.

**La consulta también se registra** (`AUDIT_QUERIED`, nivel `ACCESS`), y se registra
**después** de leer: si el registro fallara, la consulta ya se hizo y lo que no puede
quedar es el hecho sin rastro.

## 6. Permisos

`audit.view`. Ver [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Este módulo es la bitácora. Su propia consulta también se registra como acceso.

## 8. Pruebas

`AuditTest` en `stock-domain`. Pruebas de inmutabilidad y cobertura en el backend
([seguridad/auditoria.md](../../seguridad/auditoria.md) §9).

## 9. Criterios de aceptación

- [x] La bitácora no admite modificaciones (disparadores de `V2`).
- [x] Bitácora con canal, permiso y filtro, probada en navegador (`web/e2e/flow.spec.ts`).
- [ ] Verificación de la cadena de integridad desde la web. No hay prueba.
