-- Lo ya ingresado en RECEPCION por cada línea de factura (M03).
--
-- La mercancía entra al **contar**: el conteo es el momento en que el almacén toma posesión
-- de lo que de verdad llegó, y hasta aquí el saldo tenía que aparecer por un ajuste a mano,
-- así que ubicar desde RECEPCION se encontraba cero.
--
-- Esta columna es lo que hace que contar dos veces no sume dos veces: la entrada se escribe
-- por la diferencia entre lo contado ahora y lo que ya se ingresó. Hace falta porque una
-- línea con discrepancia que el supervisor descarta vuelve a PENDING y se recuenta
-- (`rejectDiscrepancy`), y sin este número el kardex contaría la mercancía dos veces.
--
-- Idempotente: se puede reaplicar sin fallar (misma regla que V14).

ALTER TABLE invoice_lines
    ADD COLUMN IF NOT EXISTS received_quantity NUMERIC(18,3) NOT NULL DEFAULT 0;

ALTER TABLE invoice_lines
    DROP CONSTRAINT IF EXISTS invoice_lines_received_not_negative;

ALTER TABLE invoice_lines
    ADD CONSTRAINT invoice_lines_received_not_negative CHECK (received_quantity >= 0);
