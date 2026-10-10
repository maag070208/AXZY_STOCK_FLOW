-- ═══════════════════════════════════════════════════════════════════════════
-- V16 — Identificadores en inglés (ADR-0017).
--
-- Los códigos de rol, de bodega y de ubicación nacieron en español
-- (`OPERADOR_ALMACEN`, `JEFE_BODEGA`, `PRINCIPAL`, `RECEPCION`, `SINUBICAR`). Las
-- migraciones anteriores ya están aplicadas y no se editan: el cambio va aquí, como datos.
--
-- Los roles los crea el arranque desde `AccessModel` con `ON CONFLICT DO NOTHING`, así que
-- una base que ya arrancó conserva la clave vieja. Renombrar la fila conserva sus
-- concesiones (`role_permissions` apunta a `id`) y sus usuarios (`user_roles` apunta a `id`).
-- ═══════════════════════════════════════════════════════════════════════════

UPDATE roles SET name = 'Administrator' WHERE key = 'ADMIN';
UPDATE roles SET key = 'OPERATIONS_MANAGER', name = 'Operations manager' WHERE key = 'GERENTE_OPERACIONES';
UPDATE roles SET key = 'WAREHOUSE_MANAGER', name = 'Warehouse manager' WHERE key = 'JEFE_BODEGA';
UPDATE roles SET key = 'WAREHOUSE_OPERATOR', name = 'Warehouse operator' WHERE key = 'OPERADOR_ALMACEN';
UPDATE roles SET name = 'Auditor' WHERE key = 'AUDITOR';

-- La bodega por defecto: su código viaja en las respuestas y en el contrato.
UPDATE warehouses SET code = 'MAIN', name = 'Main warehouse' WHERE code = 'PRINCIPAL';

-- Las dos ubicaciones que no son estantes. El código y el nombre visible son el mismo texto
-- (`locations_code_canonical` exige mayúsculas, y no hay guiones en el alfabeto de códigos).
UPDATE locations
   SET hall = 'RECEIVING', code = 'RECEIVING', position = 'RECEIVING'
 WHERE kind = 'RECEIVING' AND code = 'RECEPCION';
UPDATE locations
   SET hall = 'UNASSIGNED', code = 'UNASSIGNED', position = 'UNASSIGNED'
 WHERE kind = 'UNASSIGNED' AND code = 'SINUBICAR';
