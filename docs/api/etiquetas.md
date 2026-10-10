# Labels
> **Reference.** ZPL templates, preview, printing, job queue and reprinting.
> Last verified: 2026-10-10 (M06 closed).

Implemented in layers, like the rest of the API:

| Layer | File |
|---|---|
| Routes | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/labeling/LabelController.kt` and `LabelTemplateController.kt` |
| Use cases and ports | `api/application/src/main/kotlin/dev/axzy/stock/application/labeling/` |
| Label rules | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/labeling/LabelTemplate.kt` |
| Persistence | `api/adapters-persistence/.../labeling/JdbcLabels.kt` |
| Wiring | `api/app/src/main/kotlin/dev/axzy/stock/app/config/LabelConfig.kt` |

**The printer's confirmation is implemented** (`PrintJobController`, in
`/v1/print-jobs`): it requires a service credential in `X-Service-Key`, it checks it **before
anything else** —even before checking whether the job exists, because a `404` to someone without
a credential would tell them which identifiers are valid— and it is **the only place where a
label becomes printed**. No user endpoint does it: the system does not self-certify.

The credential's hash is **SHA-256** and not Argon2id, which is the one for passwords: a person's password is short and guessable and each attempt has to be made expensive; a service secret is 32 random bytes, there is nothing to guess, and instead it has to be possible to **look up by hash** on each call from the printer.

Three decisions the implementation left fixed:

- **The ZPL validation happens on save** (`^XA` and `^XZ`), not on printing: without the closing
  the printer gives no error, it leaves the label blank and it is discovered by spending paper.
- **The reprint needs a link that the schema did not have.** The contract asks for "a new job
  linked to the original", and there was no column for that link: migration
  **V14** adds `print_jobs.reprinted_from_id`. The reprint also copies the payload of the original, so that it prints the same.
- **The size in millimeters is published with one decimal.** The contract says `152.4` and the
  calculation from inches gave `152.39999999999998`, which is what floating point gives
  with `6 × 25.4`. A tenth of a millimeter is more precision than any label has.

Pagination is in [convenciones.md](convenciones.md); the codes, in
[errores.md](errores.md).

## `GET|POST /v1/label-templates`, `GET|PUT /v1/label-templates/{id}`

The listing and the detail require `label.template_view`; creation (`POST`) and
editing (`PUT`), `label.template_edit`.

```json
{
  "id": 1, "code": "receipt", "name": "Goods receipt label",
  "description": null, "size": "4x6", "isActive": true,
  "fieldSchema": {
    "productCode": { "type": "string", "label": "Code", "required": true },
    "name": { "type": "string", "label": "Name", "required": true },
    "quantity": { "type": "number", "label": "Quantity", "required": false }
  }
}
```

`zpl` **is not returned in the listing** (it is large and not needed): only in
the detail. The listing accepts `onlyActive` (`false` by default) and returns an
array, **without a pagination envelope** (it is a closed catalog that the interface
needs in full; see [convenciones.md §1.4](convenciones.md)).

Creation uses `LabelTemplateCreateRequest` (`code`, `name`, `zpl` mandatory;
`description`, `size`, `fieldSchema`, `isActive` optional) and answers **201** with
the detail. Editing uses `LabelTemplateUpdateRequest`: the fields that are not
sent remain as they were.

**The ZPL is validated ON SAVE**: it must contain `^XA` and `^XZ`, or it answers `422
INVALID_ZPL`. Without the closing, the printer gives no error — it leaves the label blank
or the job in the queue, and that is discovered by spending paper. Detail: `404
LABEL_TEMPLATE_NOT_FOUND`, `400 INVALID_ID`.

## `POST /v1/labels/preview`

Permission: `label.print`. Returns the resolved ZPL **without printing**. For the web and the mobile app.

```json
{ "templateId": 1, "data": { "productCode": "ABRAA1B", "name": "...", "quantity": "100" } }
```

**200:**

```json
{ "zpl": "^XA...^XZ", "preview": { "widthMm": 101.6, "heightMm": 152.4, "copies": 1 } }
```

**The resolution is done by the DOMAIN**, the same function used by the dispatch to the
printer: if it were resolved separately, the preview could show something different
from what is printed, and what is seen would be approved.

A missing mandatory field **returns `422 MISSING_LABEL_FIELD`, not a half-finished
label**: leaving the placeholder prints `{{productCode}}` on the shelf, and replacing it with empty
prints a label that looks correct and is not.

The size in millimeters is calculated by the server: if each client did it, the web preview and
the mobile one would show different sizes for the same template. `404 LABEL_TEMPLATE_NOT_FOUND` if the template does not exist.

## `POST /v1/labels/print`

Permission: `label.print`.

```json
{ "templateId": 1, "copies": 2, "data": [ { "productCode": "ABRAA1B" } ] }
```

**202 Accepted** with `jobIds`. **The job stays `PENDING` until the printer
confirms.**

```json
{ "jobIds": [501, 502], "status": "PENDING" }
```

`copies` goes from 1 to 100 and defaults to 1. The ZPL is resolved **before** enqueuing:
a job with incomplete data would be created anyway and it would have to be discovered when
printing, with the label already in the queue. Errors: `422 MISSING_LABEL_FIELD`,
`422 LABEL_TEMPLATE_INACTIVE`, `422 FIELD_REQUIRED`, `400 INVALID_PARAMETER`,
`404 LABEL_TEMPLATE_NOT_FOUND`.

## `GET /v1/labels/jobs/{id}`, `GET /v1/labels/jobs?status=FAILED`

Permission: `label.queue_view`. The listing filters by `status` and is paginated with `page` and
`size`.

```json
{
  "id": 501, "templateCode": "receipt", "copies": 2,
  "status": "PRINTED", "attempts": 1, "lastError": null,
  "printedAt": "2026-10-07T21:30:00Z", "createdAt": "2026-10-07T21:29:50Z"
}
```

`status`: `PENDING` | `SENT` | `PRINTED` | `FAILED`. **A label is not marked
printed until the printer confirms.** The legacy marked it without waiting for the
response, so if the Zebra was off, the label was lost forever.
`?status=FAILED` is the query that makes the queue useful: a label that did not come out
must be findable.

Detail: `404 PRINT_JOB_NOT_FOUND`, `400 INVALID_ID`.

## `POST /v1/labels/jobs/{id}/reprint`

Permission: `label.reprint`. Requires a reason (`{"reason": "..."}`) and generates a **new job**
linked to the original, it does not reuse the record: the traceability of a reprint requires knowing who
requested it and why. **202** with the new job, which is also born `PENDING`.

`label.reprint` and not `label.print`: reprinting uses a second label, and the
catalog separates them on purpose. Errors: `422 REASON_REQUIRED`, `404
PRINT_JOB_NOT_FOUND`.

## `PATCH /v1/print-jobs/{id}` — integration with the printer

**It is the only route that does not live under `/v1/labels`**, and even so it hangs from a resource:
it is the update of the state of ONE print job. The route hangs from the
resource it modifies; a route without a resource (`PATCH /v1/prints`) would not describe the
effect nor be discoverable by reading the labels documentation.

It is the call the Zebra makes. **It does not require a user JWT, but a service credential
in the `X-Service-Key` header** (table `service_api_keys`; the secret is
stored hashed and is shown only once when it is created). The legacy had this endpoint
**open without any credential**, so anyone on the network could mark
jobs as printed.

**`X-Service-Key` and not `Authorization`** so that it is clear in the logs that it is not a person: the
audit log stores `SERVICE_PRINTER` as the channel and the key's id, and this way a job
marked as printed with no user behind it is distinguished from one done by someone.

```json
{ "status": "PRINTED", "error": null }
```

`status`: `PRINTED` | `FAILED`. The `jobId` goes in the route, not in the body: it is
the resource being updated, and repeating the call must not print twice.

**200** with the confirmation:

```json
{ "jobId": 501, "status": "PRINTED", "error": null }
```

**Errors:** `401 INVALID_SERVICE_KEY`, `404 PRINT_JOB_NOT_FOUND` if the job does not
exist, `409 LABEL_ALREADY_PRINTED` if the job is already `PRINTED` — a
label is not printed twice because of a retry from the printer.

**This is where a label becomes printed, and only here.** No user endpoint does it:
the system does not self-certify.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/LabelFlowTest.kt` | L1, L3 and L5 over real HTTP, the ZPL validation, the size in millimeters, the escaping of control characters and the per-route permissions |
| `api/app/src/test/kotlin/dev/axzy/stock/app/PrintJobConfirmationTest.kt` | L2 and L4: the printer confirms only once, the failure leaves the job findable, the credential is worth nothing else, the revoked one stops working and the audit log stores the channel and the key |
