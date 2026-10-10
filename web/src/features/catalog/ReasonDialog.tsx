import { ITAlert, ITButton, ITDialog, ITFlex, ITInput } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";

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
  confirmLabel = "Desactivar",
  placeholder = "Por qué se da de baja",
  errorTitle = "No se pudo desactivar",
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
          label="Motivo"
          placeholder={placeholder}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          autoFocus
        />

        {error && <ITAlert variant="error" title={errorTitle}>{error}</ITAlert>}

        <ITFlex justify="end" gap={2}>
          <ITButton label="Cancelar" variant="outline" onClick={onCancel} disabled={busy} />
          <ITButton
            label={busy ? "Desactivando…" : confirmLabel}
            color="danger"
            onClick={() => onConfirm(reason.trim())}
            disabled={busy || reason.trim() === ""}
          />
        </ITFlex>
      </ITFlex>
    </ITDialog>
  );
}
