-- V10: el total de un producto se recalcula con el producto bloqueado.
--
-- **El defecto.** `recalcular_total_producto` (V6) suma las ubicaciones y el tránsito
-- y después escribe el total en `product_stock`. Con dos transacciones concurrentes sobre
-- el mismo producto, en estantes distintos, ninguna ve el cambio de la otra (aún no
-- confirmado). La que escribe último pisa el total con una suma que no incluye la
-- mercancía de la primera: se reprodujo con 100+10 y 100+5, que dejó el total en 205 con
-- 215 reales. `comprobar_proyecciones()` lo detecta, pero la escritura ya se perdió.
--
-- **La corrección.** Bloquear la fila del producto ANTES del `SUM`. El segundo recálculo
-- espera a que el primero confirme, y entonces su suma ya ve ambos cambios. Todo recálculo
-- toma el bloqueo al entrar; los movimientos bloquean primero sus ubicaciones y después el
-- producto, en ese orden, así que no hay ciclo de espera.
--
-- Solo cambia el cuerpo: la firma y los privilegios concedidos en V6 se conservan.

CREATE OR REPLACE FUNCTION recalcular_total_producto(p_product_id BIGINT)
RETURNS NUMERIC AS $$
DECLARE
    total NUMERIC;
BEGIN
    -- Serializa los recálculos del mismo producto. Sin esta línea el SUM de abajo no ve
    -- las escrituras concurrentes sin confirmar, y el total se pierde (ver cabecera).
    PERFORM 1 FROM products WHERE id = p_product_id FOR UPDATE;

    SELECT
        COALESCE((SELECT SUM(quantity) FROM stock_by_location WHERE product_id = p_product_id), 0)
      + COALESCE((SELECT SUM(quantity) FROM stock_in_transit
                  WHERE product_id = p_product_id AND received_at IS NULL), 0)
    INTO total;

    INSERT INTO product_stock (product_id, quantity, updated_at)
    VALUES (p_product_id, total, now())
    ON CONFLICT (product_id)
    DO UPDATE SET quantity = total, updated_at = now();

    RETURN total;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION recalcular_total_producto IS
    'Recalcula product_stock como la SUMA de las ubicaciones mas el transito NO '
    'recibido, con el producto bloqueado para que dos recalculos concurrentes '
    'no pierdan la escritura del otro.';
