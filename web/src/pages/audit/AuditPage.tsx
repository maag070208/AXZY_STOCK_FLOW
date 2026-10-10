import { ITDatePicker, ITInput, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { auditApi, type AuditEntry } from "@entities/audit";
import { asDate, toIsoDay } from "@shared/lib/day";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";

const LEVELS = ["CRITICAL", "RELEVANT", "ACCESS", "TECHNICAL"];
const RESULTS = ["SUCCESS", "FAILURE", "DENIED", "PARTIAL"];

/**
 * La bitácora: quién hizo qué, sobre qué, cuándo y desde dónde. Solo lectura, y la lectura queda
 * registrada como acceso. Los filtros son los que la API admite; el nivel y el resultado son
 * listas cerradas, así que van en selectores y no en texto libre.
 */
export default function AuditPage() {
  const [entityType, setEntityType] = useState("");
  const [action, setAction] = useState("");
  const [level, setLevel] = useState("");
  const [result, setResult] = useState("");
  const [from, setFrom] = useState<Date | null>(null);
  const [to, setTo] = useState<Date | null>(null);
  const debouncedEntity = useDebouncedValue(entityType.trim(), 400);
  const debouncedAction = useDebouncedValue(action.trim(), 400);

  const externalFilters = useMemo(() => {
    const fromDay = toIsoDay(from);
    const toDay = toIsoDay(to);
    return {
      ...(debouncedEntity ? { entityType: debouncedEntity } : {}),
      ...(debouncedAction ? { action: debouncedAction } : {}),
      ...(level ? { level } : {}),
      ...(result ? { result } : {}),
      ...(fromDay ? { from: fromDay } : {}),
      ...(toDay ? { to: toDay } : {}),
    };
  }, [debouncedEntity, debouncedAction, level, result, from, to]);

  const columns: Column<AuditEntry>[] = [
    { key: "occurredAt", label: "Cuándo", type: "string", render: (row) => new Date(row.occurredAt).toLocaleString("es-MX") },
    { key: "actorName", label: "Quién", type: "string", render: (row) => row.actorName ?? row.actorUsername ?? row.channel },
    { key: "action", label: "Acción", type: "string" },
    { key: "entityType", label: "Sobre", type: "string", render: (row) => (row.entityLabel ? `${row.entityType} ${row.entityLabel}` : row.entityType) },
    { key: "level", label: "Nivel", type: "string" },
    { key: "result", label: "Resultado", type: "string" },
    { key: "channel", label: "Canal", type: "string" },
    { key: "reason", label: "Motivo", type: "string", render: (row) => row.reason ?? "—" },
  ];

  return (
    <Page title="Auditoría" description="Quién hizo qué, cuándo y desde dónde. Cada escritura del sistema aparece aquí." breadcrumbs={[{ label: "Inicio", href: "#/" }, { label: "Auditoría" }]}>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="w-44">
          <ITInput size="sm" name="entityType" label="Tipo de entidad" value={entityType} onChange={(e) => setEntityType(e.target.value)} />
        </div>
        <div className="w-56">
          <ITInput size="sm" name="action" label="Acción" value={action} onChange={(e) => setAction(e.target.value)} />
        </div>
        <div className="w-40">
          <ITSelect size="sm" name="level" label="Nivel" value={level} placeholder="Todos"
            options={LEVELS.map((value) => ({ value, label: value }))} onChange={(e) => setLevel(e.target.value)} />
        </div>
        <div className="w-40">
          <ITSelect size="sm" name="result" label="Resultado" value={result} placeholder="Todos"
            options={RESULTS.map((value) => ({ value, label: value }))} onChange={(e) => setResult(e.target.value)} />
        </div>
        <div className="w-44">
          <ITDatePicker name="from" label="Desde" value={from ?? undefined} onChange={(e) => setFrom(asDate(e.target.value))} />
        </div>
        <div className="w-44">
          <ITDatePicker name="to" label="Hasta" value={to ?? undefined} onChange={(e) => setTo(asDate(e.target.value))} />
        </div>
      </div>
      <DataTable<AuditEntry>
        columns={columns}
        fetchPage={(query) => auditApi.log(query, {})}
        externalFilters={externalFilters}
      />
    </Page>
  );
}
