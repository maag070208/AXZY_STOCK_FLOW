import { ITAlert, ITButton, ITDialog, ITFlex, ITInput } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import i18n from "@shared/i18n";

interface Props {
  open: boolean;
  title: string;
  /** Qué se está dando de baja, con su nombre, para que nadie confirme a ciegas. */
  description: string;
  confirmLabel?: string;
  /** Lo que se escribe en el campo. Por defecto, el de la baja. */
  placeholder?: string;
  /** Título del aviso cuando la API rechaza la acción. */
  errorTitle?: string;
  busy?: boolean;
  error?: string | null;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

/**
 * La baja lógica, con motivo obligatorio.
 *
 * El motivo lo exige la API (`REASON_REQUIRED`, 422): una desactivación sin
 * explicación es un hueco en la bitácora que nadie puede rellenar después. El
 * formulario no lo inventa ni lo pone por defecto.
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

  // Cada apertura empieza en blanco: reutilizar el motivo anterior es la forma
  // más rápida de que la bitácora diga algo que no pasó.
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
