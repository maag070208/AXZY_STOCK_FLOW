-- V11: el rol de la API, separado del que migra; y el índice de la cadena de auditoría.
--
-- ═══════════════════════════════════════════════════════════════════════════
-- 1. EL ROL DE LA API
-- ═══════════════════════════════════════════════════════════════════════════
--
-- **El defecto.** V2, V4, V6, V8 y V9 conceden sus permisos a `current_user`: quien
-- migra es quien después usa la base. Con un solo rol, un defecto en la API puede
-- hacer `DROP TABLE`, y la regla «el kardex solo crece» depende únicamente del
-- disparador.
--
-- **La corrección.** `stock_api` es un rol de GRUPO, sin login. Reúne exactamente lo
-- que la API necesita. Cada entorno crea su usuario de conexión y lo hace miembro
-- (`CREATE ROLE stock_app LOGIN PASSWORD '…' IN ROLE stock_api`): los permisos se
-- versionan aquí y las credenciales nunca entran en una migración.
--
-- Lo que `stock_api` NO puede hacer, además de lo que ya bloquean los disparadores:
--   · crear, alterar o borrar tablas (no tiene CREATE en el esquema);
--   · UPDATE ni DELETE sobre `kardex` ni `audit_log`;
--   · escribir en el historial de migraciones.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stock_api') THEN
        CREATE ROLE stock_api NOLOGIN;
    END IF;
END $$;

GRANT USAGE ON SCHEMA public TO stock_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO stock_api;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO stock_api;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO stock_api;

-- Lo que se cree a partir de ahora hereda los mismos permisos: una migración futura
-- que añada una tabla no tiene que acordarse de conceder nada.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stock_api;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO stock_api;

-- ── Los dos libros que solo crecen ─────────────────────────────────────────
-- El disparador ya rechaza la mutación; quitar el permiso hace que ni siquiera se
-- intente. Dos defensas independientes: si alguien desactiva una, queda la otra.
REVOKE UPDATE, DELETE ON kardex FROM stock_api;
REVOKE UPDATE, DELETE ON audit_log FROM stock_api;

DO $$
DECLARE
    particion RECORD;
BEGIN
    FOR particion IN
        SELECT hija.relname
        FROM pg_inherits i
        JOIN pg_class hija ON hija.oid = i.inhrelid
        JOIN pg_class padre ON padre.oid = i.inhparent
        WHERE padre.relname = 'audit_log'
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE ON %I FROM stock_api', particion.relname);
    END LOOP;
END $$;

-- La API lee el historial para comprobar al arrancar que la base está al día; no lo escribe.
REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM stock_api;

-- ═══════════════════════════════════════════════════════════════════════════
-- 2. LAS PARTICIONES DE LA BITÁCORA
-- ═══════════════════════════════════════════════════════════════════════════
--
-- V2 crea la partición del mes en curso y las tres siguientes, y dice que un job las
-- planifica. Ese job no existía. `afterMigrate.sql` las crea en cada despliegue, y la
-- API las asegura con una tarea programada: por eso la función pasa a ejecutarse con
-- los permisos de su dueño (`SECURITY DEFINER`), que es quien puede crear tablas.
--
-- Es una función estrecha a propósito: recibe una fecha y solo sabe crear
-- `audit_log_AAAA_MM`. No hay texto del llamador que llegue a un `EXECUTE`.
CREATE OR REPLACE FUNCTION audit_log_ensure_partition(target_month DATE)
RETURNS TEXT AS $$
DECLARE
    inicio DATE := date_trunc('month', target_month)::DATE;
    fin    DATE := (inicio + INTERVAL '1 month')::DATE;
    nombre TEXT := format('audit_log_%s', to_char(inicio, 'YYYY_MM'));
BEGIN
    IF to_regclass(nombre) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            nombre, inicio, fin
        );
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM PUBLIC', nombre);
        -- Los permisos por defecto concederían UPDATE y DELETE sobre la partición nueva.
        EXECUTE format('REVOKE UPDATE, DELETE ON %I FROM stock_api', nombre);
        -- Los triggers se aplican POR PARTICIÓN: los del padre no heredan.
        PERFORM audit_log_attach_immutability(nombre);
        RETURN format('partición %s creada (%s a %s)', nombre, inicio, fin);
    END IF;
    RETURN format('partición %s ya existía', nombre);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

REVOKE ALL ON FUNCTION audit_log_ensure_partition(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit_log_ensure_partition(DATE) TO stock_api;

-- ═══════════════════════════════════════════════════════════════════════════
-- 3. EL ÍNDICE DE LA CADENA DE HASH
-- ═══════════════════════════════════════════════════════════════════════════
--
-- **El defecto.** `audit_log_record` busca el eslabón anterior en CADA escritura:
--
--     SELECT record_hash FROM audit_log ORDER BY sequence_no DESC LIMIT 1;
--
-- y no había índice por `sequence_no`. El plan era recorrer todas las particiones
-- completas y ordenarlas. Como cada escritura del sistema pasa por la bitácora, y
-- además bajo un cerrojo global, el coste de CUALQUIER operación crecía con el
-- tamaño de la bitácora.
--
-- **La corrección.** Con el índice, el plan lee la última entrada de cada partición
-- y se queda con la mayor: el coste depende del número de particiones, no de filas.
CREATE INDEX audit_log_sequence_no_idx ON audit_log (sequence_no DESC);
