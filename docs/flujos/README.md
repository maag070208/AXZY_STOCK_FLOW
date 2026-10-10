# StockFlow flows
> **Normative.** The flows each part of the system must fulfill. They are the reference for writing
> the acceptance tests and the code. Last revised: 2026-10-09.

## Index

| Flow | What it covers | Module |
|---|---|---|
| [autenticacion-y-permisos.md](autenticacion-y-permisos.md) | Sign-in, session, password change, access decision | Identity |
| [catalogo.md](catalogo.md) | Products, locations, warehouses, import of the Excel | Catalog |
| [existencias-y-ajustes.md](existencias-y-ajustes.md) | Movements, adjustments, locate, move, ledger, total per product | Stock |
| [recepcion.md](recepcion.md) | Invoice, physical count, discrepancies, approval, location | Receiving |
| [surtido-y-erp.md](surtido-y-erp.md) | Order, packing, shortages, confirmation, export to the ERP | Picking |
| [traslados.md](traslados.md) | Transfer between locations and between warehouses, transit | Transfers |
| [etiquetas.md](etiquetas.md) | Templates, print queue, confirmation, reprint | Labels |
| [auditoria.md](auditoria.md) | Write log, immutability, query | Audit |

## How a flow is read

Each flow has the same sections:

1. **Objective and actors**: who uses it and for what.
2. **Preconditions**: what has to exist beforehand.
3. **Flow**: the API sequence diagram, with the steps that are fulfilled in order.
4. **States**: the allowed transitions. Any transition not listed here is rejected.
5. **Acceptance tests**: scenarios in Given / When / Then format. They are the minimum list
   that has to pass before the flow is considered finished.
6. **Errors**: the codes it can return, with their meaning.
7. **Permissions**: which permission each step requires.
8. **Audit log**: what it writes to the audit log.
9. **Rules and decisions**: links to the ADRs that justify them.

## Diagram conventions

- Participants: `Operator` (app or web), `API`, `Use case` (inside the API), `Database` (PostgreSQL).
- A step inside `Database` that ends with `COMMIT` marks the end of a transaction.
- The `alt` and `opt` branches are the ones the tests must cover.

## What is not in the flows

- Detail of each endpoint (body, response, fields): [api/](../api/convenciones.md).
- How each step is implemented in code: [arquitectura/api-spring.md](../arquitectura/api-spring.md).
- Rules that do not belong to a specific flow: [seguridad/](../seguridad/roles-permisos.md).

## Correspondence with the previous modules

Modules M01–M09 in [modulos/](../modulos/README.md) described the behavior of the previous system
with Ktor code paths. The business rules of those modules remain in force and the flows in
this folder reorganize them by process. When a flow and a module contradict each other,
the flow prevails and the module is corrected.
