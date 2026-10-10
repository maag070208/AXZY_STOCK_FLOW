-- Se ejecuta después de CADA `flyway migrate`, haya o no migraciones nuevas.
--
-- Asegura las particiones mensuales de la bitácora del mes en curso y los 18 siguientes.
-- V2 solo creaba cuatro meses y dejaba el resto a un job que no existía: con esto, cada
-- despliegue extiende la ventana. Si aun así faltara una, la fila cae en `audit_log_default`
-- en lugar de fallar, y la API lo reporta en su comprobación de salud.
SELECT audit_log_ensure_partition((date_trunc('month', CURRENT_DATE) + make_interval(months => n))::date)
FROM generate_series(0, 18) AS n;
