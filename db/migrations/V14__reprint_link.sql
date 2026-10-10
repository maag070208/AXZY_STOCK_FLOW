-- ═══════════════════════════════════════════════════════════════════════════
-- EL ENLACE DE UNA REIMPRESIÓN
-- ═══════════════════════════════════════════════════════════════════════════
--
-- El contrato de etiquetas exige que reimprimir genere **un trabajo nuevo enlazado al
-- original**, y no que reutilice el registro: «la trazabilidad de una reimpresión
-- exige saber quién la pidió y por qué». El quién y el por qué ya iban a la bitácora;
-- el enlace, en cambio, no existía en ninguna columna, así que la única forma de
-- reconstruir «esta etiqueta es la segunda de aquella» era leer los registros de
-- auditoría y cruzarlos a mano.
--
-- La columna lo hace consultable: `select * from print_jobs where reprinted_from_id = 501`.
-- `IF NOT EXISTS` a propósito: una migración se aplica una vez por Flyway, pero si
-- alguien la aplicó a mano antes —como pasó al escribir esta—, un `ADD COLUMN` seco
-- falla y **deja el arranque del servicio de migración en rojo**. Poder re-aplicarla no
-- cuesta nada y evita un incidente de despliegue.
ALTER TABLE print_jobs
    ADD COLUMN IF NOT EXISTS reprinted_from_id BIGINT REFERENCES print_jobs(id);

COMMENT ON COLUMN print_jobs.reprinted_from_id IS
    'El trabajo original, cuando este es una reimpresión. NULL en la primera impresión. '
    'Apunta a otro trabajo y no al revés para que el original no se modifique nunca: '
    'una reimpresión no cambia el pasado, añade un hecho nuevo.';

CREATE INDEX IF NOT EXISTS print_jobs_reprint_idx ON print_jobs (reprinted_from_id) WHERE reprinted_from_id IS NOT NULL;
