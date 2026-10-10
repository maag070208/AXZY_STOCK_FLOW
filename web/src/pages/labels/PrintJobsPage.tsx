import { ITBadget, ITButton, ITSelect, type Column } from "@axzydev/axzy_ui_system";
import { useMemo, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import type { AppDispatch, RootState } from "@app/store";
import { showToast } from "@app/toast/toast.slice";
import ReasonDialog from "@features/catalog/ReasonDialog";
import { can } from "@entities/session";
import { labelsApi, type PrintJob } from "@entities/labels";

import { DataTable } from "@shared/ui/data-table/DataTable";
import { Page } from "@shared/ui/page/Page";
import { FaTags } from "react-icons/fa";
import i18n from "@shared/i18n";
import { describeError } from "@shared/api/errorMessage";

const STATUSES = ["PENDING", "PRINTED", "FAILED"];

/**
 * The print queue. A label becomes `PRINTED` only when the printer confirms it: requesting it
 * does not mark it as printed. A failure stays visible with its reason and attempt number.
 *
 * Reprinting asks for a reason and creates a **new** job linked to the original; the original does not change.
 */
export default function PrintJobsPage() {
  const dispatch = useDispatch<AppDispatch>();
  const user = useSelector((state: RootState) => state.auth.user);
  const [status, setStatus] = useState("");
  const [reprinting, setReprinting] = useState<PrintJob | null>(null);
  const [reprintError, setReprintError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [version, setVersion] = useState(0);
  const externalFilters = useMemo(() => (status ? { status } : undefined), [status]);

  const reprint = async (reason: string) => {
    if (!reprinting) return;
    setBusy(true);
    setReprintError(null);
    try {
      await labelsApi.reprint(reprinting.id, reason);
      setReprinting(null);
      dispatch(showToast(i18n.t("labels.reprintRequested"), "success"));
      setVersion((current) => current + 1);
    } catch (caught) {
      setReprintError(describeError(caught, "labels.reprintFailed"));
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<PrintJob>[] = [
    { key: "createdAt", label: i18n.t("labels.requested"), type: "string", render: (row) => new Date(row.createdAt).toLocaleString("en-US") },
    { key: "templateCode", label: i18n.t("labels.template"), type: "string" },
    { key: "copies", label: i18n.t("labels.copies"), type: "number" },
    {
      key: "status",
      label: i18n.t("common.status"),
      type: "string",
      render: (row) => <ITBadget label={row.status} color="secondary" size="sm" />,
    },
    { key: "attempts", label: i18n.t("labels.attempts"), type: "number" },
    { key: "printedAt", label: i18n.t("labels.printed"), type: "string", render: (row) => (row.printedAt ? new Date(row.printedAt).toLocaleString("en-US") : "—") },
    { key: "lastError", label: i18n.t("fulfillment.failureReason"), type: "string", render: (row) => row.lastError ?? "—" },
    { key: "reprintedFromId", label: i18n.t("labels.reprintOf"), type: "number", render: (row) => (row.reprintedFromId ? `#${row.reprintedFromId}` : "—") },
    ...(can(user, "label.reprint")
      ? [
          {
            key: "actions",
            label: "",
            type: "string" as const,
            sortable: false,
            render: (row: PrintJob) => <ITButton label={i18n.t("labels.reprint")} size="sm" variant="outline" onClick={() => setReprinting(row)} />,
          },
        ]
      : []),
  ];

  return (
    <Page icon={<FaTags size={18} />} title={i18n.t("labels.queue")} description={i18n.t("labels.description")} breadcrumbs={[{ label: i18n.t("nav.home"), href: "#/" }, { label: i18n.t("nav.labels") }]}>
      <div className="mb-3 w-56">
        <ITSelect size="sm" name="status" label={i18n.t("common.status")} value={status} placeholder={i18n.t("common.all")}
          options={STATUSES.map((value) => ({ value, label: value }))} onChange={(e) => setStatus(e.target.value)} />
      </div>
      <DataTable<PrintJob>
        columns={columns}
        fetchPage={(query) => labelsApi.printJobs(query, {})}
        externalFilters={externalFilters}
        reloadTrigger={version}
      />

      <ReasonDialog
        open={reprinting !== null}
        title={i18n.t("labels.reprintLabel")}
        description={reprinting ? i18n.t("labels.reprintDescription", { id: reprinting.id, template: reprinting.templateCode }) : ""}
        confirmLabel={i18n.t("labels.reprint")}
        placeholder={i18n.t("labels.reprintReasonPlaceholder")}
        errorTitle={i18n.t("labels.reprintFailedShort")}
        busy={busy}
        error={reprintError}
        onCancel={() => setReprinting(null)}
        onConfirm={(reason) => void reprint(reason)}
      />
    </Page>
  );
}
