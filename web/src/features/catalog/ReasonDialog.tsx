import { ITAlert, ITButton, ITDialog, ITFlex, ITInput } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import i18n from "@shared/i18n";

interface Props {
  open: boolean;
  title: string;
  /** What is being deleted, with its name, so that nobody confirms blindly. */
  description: string;
  confirmLabel?: string;
  /** What is typed in the field. By default, the one for the soft delete. */
  placeholder?: string;
  /** Title of the notice when the API rejects the action. */
  errorTitle?: string;
  busy?: boolean;
  error?: string | null;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

/**
 * Soft delete, with a mandatory reason.
 *
 * The reason is required by the API (`REASON_REQUIRED`, 422): a deactivation without
 * explanation is a gap in the audit log that no one can fill in later. The
 * form does not invent it nor set it by default.
 */
export default function ReasonDialog({
  open,
  title,
  description,
  confirmLabel = i18n.t("common.deactivate"),
  placeholder = i18n.t("catalog.deactivateReasonPlaceholder"),
  errorTitle = i18n.t("catalog.deactivateFailed"),
  busy = false,
  error = null,
  onCancel,
  onConfirm,
}: Props) {
  const [reason, setReason] = useState("");

  // Each opening starts blank: reusing the previous reason is the way
  // faster than the audit log saying something that did not happen.
  useEffect(() => {
    if (open) setReason("");
  }, [open]);

  return (
    <ITDialog isOpen={open} onClose={onCancel} title={title}>
      <ITFlex direction="column" gap={4}>
        <p className="text-sm text-slate-600">{description}</p>

        <ITInput
          name="reason"
          label={i18n.t("common.reason")}
          placeholder={placeholder}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          autoFocus
        />

        {error && <ITAlert variant="error" title={errorTitle}>{error}</ITAlert>}

        <ITFlex justify="end" gap={2}>
          <ITButton label={i18n.t("common.cancel")} variant="outline" onClick={onCancel} disabled={busy} />
          <ITButton
            label={busy ? i18n.t("catalog.deactivating") : confirmLabel}
            color="danger"
            onClick={() => onConfirm(reason.trim())}
            disabled={busy || reason.trim() === ""}
          />
        </ITFlex>
      </ITFlex>
    </ITDialog>
  );
}
