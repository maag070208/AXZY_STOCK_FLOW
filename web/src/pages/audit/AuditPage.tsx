import { ITDatePicker, ITInput, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { auditApi, type AuditEntry } from "@entities/audit";
import { asDate, toIsoDay } from "@shared/lib/day";
import { useDebouncedValue } from "@shared/lib/useDebouncedValue";
import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import { FaHistory } from "react-icons/fa";
import i18n from "@shared/i18n";

const LEVELS = ["CRITICAL", "RELEVANT", "ACCESS", "TECHNICAL"];
const RESULTS = ["SUCCESS", "FAILURE", "DENIED", "PARTIAL"];

/**
 * The audit log: who did what, to what, when and from where. Read-only, and the reading is recorded
 * recorded as access. The filters are the ones the API supports; the level and the result are
 * closed lists, so they go in selects and not in free text.
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
    { key: "occurredAt", label: i18n.t("common.when"), type: "string", render: (row) => new Date(row.occurredAt).toLocaleString("en-US") },
    { key: "actorName", label: i18n.t("common.who"), type: "string", render: (row) => row.actorName ?? row.actorUsername ?? row.channel },
    { key: "action", label: i18n.t("common.action"), type: "string" },
    { key: "entityType", label: i18n.t("common.envelope"), type: "string", render: (row) => (row.entityLabel ? `${row.entityType} ${row.entityLabel}` : row.entityType) },
    { key: "level", label: i18n.t("common.level"), type: "string" },
    { key: "result", label: i18n.t("common.result"), type: "string" },
    { key: "channel", label: i18n.t("common.channel"), type: "string" },
    { key: "reason", label: i18n.t("common.reason"), type: "string", render: (row) => row.reason ?? "—" },
  ];

  return (
    <Page icon={<FaHistory size={18} />} title={i18n.t("nav.audit")} description={i18n.t("audit.description")} breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.audit") }]}>
      <div className="mb-3 flex flex-wrap items-end gap-3">
        <div className="w-44">
          <ITInput size="sm" name="entityType" label={i18n.t("audit.entityType")} value={entityType} onChange={(e) => setEntityType(e.target.value)} />
        </div>
        <div className="w-56">
          <ITInput size="sm" name="action" label={i18n.t("common.action")} value={action} onChange={(e) => setAction(e.target.value)} />
        </div>
        <div className="w-40">
          <ITSelect size="sm" name="level" label={i18n.t("common.level")} value={level} placeholder={i18n.t("common.all")}
            options={LEVELS.map((value) => ({ value, label: value }))} onChange={(e) => setLevel(e.target.value)} />
        </div>
        <div className="w-40">
          <ITSelect size="sm" name="result" label={i18n.t("common.result")} value={result} placeholder={i18n.t("common.all")}
            options={RESULTS.map((value) => ({ value, label: value }))} onChange={(e) => setResult(e.target.value)} />
        </div>
        <div className="w-44">
          <ITDatePicker name="from" label={i18n.t("common.from")} value={from ?? undefined} onChange={(e) => setFrom(asDate(e.target.value))} />
        </div>
        <div className="w-44">
          <ITDatePicker name="to" label={i18n.t("common.to")} value={to ?? undefined} onChange={(e) => setTo(asDate(e.target.value))} />
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
