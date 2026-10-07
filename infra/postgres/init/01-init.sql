-- ============================================================================
-- Inicialización de PostgreSQL (se ejecuta UNA vez, al crear el volumen).
--
-- Si cambias este archivo y quieres re-ejecutarlo:
--   docker compose down -v && docker compose up -d      ⚠️ BORRA LOS DATOS
-- ============================================================================

-- Extensión para análisis de consultas. Sirve para detectar el N+1 que el
-- legacy tenía de forma sistemática (hallazgo R-13).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Búsqueda de texto sin distinguir mayúsculas ni acentos, para el buscador
-- de productos por nombre. El legacy hacía búsquedas por prefijo sin índice.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- UUID para client_uuid (idempotencia del móvil offline) y correlation_id
-- de la auditoría.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- Base de datos SEPARADA para los tests de integración.
--
-- Es una decisión deliberada: los tests de repositorio y de API necesitan
-- crear y destruir esquemas. Si comparten base con desarrollo, un test mal
-- escrito puede borrar datos de trabajo. Con Testcontainers se usa una base
-- efímera, pero esta existe para poder correr tests contra este contenedor
-- cuando conviene inspeccionar el resultado.
-- ============================================================================
-- `OWNER CURRENT_USER` no es sintaxis válida en CREATE DATABASE: el owner
-- debe ser un identificador. Se usa psql \gexec para resolverlo en runtime
-- sin incrustar el nombre del rol en el archivo.
SELECT format('CREATE DATABASE stock_test OWNER %I', current_user) AS stmt
\gexec

-- ============================================================================
-- Permisos: el rol de la aplicación NO puede modificar el ledger ni la
-- auditoría. Se aplica a nivel de base de datos, no de código, porque una
-- regla que solo vive en el código se salta con un script de mantenimiento.
--
-- NOTA: las tablas aún no existen (las crea Flyway en la Fase 1). Los GRANT
-- sobre tablas concretas se aplican en una migración posterior, no aquí. Lo
-- que sí se configura ahora es el comportamiento por defecto.
-- ============================================================================

-- El esquema public no debe permitir CREATE a cualquiera por defecto.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Comentario de documentación del clúster.
COMMENT ON DATABASE stock IS
  'Almacen Integral. El ledger (kardex) y la auditoria (audit_log) son append-only: ver PLAN_AUDITORIA.md';
