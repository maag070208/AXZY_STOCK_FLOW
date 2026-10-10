import type { ReactNode } from "react";

export type KpiTone = "emerald" | "sky" | "amber" | "rose" | "violet" | "neutral";

const TONES: Record<KpiTone, { icon: string; hint: string }> = {
  emerald: { icon: "bg-emerald-50 text-emerald-600", hint: "text-emerald-700" },
  sky: { icon: "bg-sky-50 text-sky-600", hint: "text-slate-500" },
  amber: { icon: "bg-amber-50 text-amber-600", hint: "text-amber-700" },
  rose: { icon: "bg-rose-50 text-rose-600", hint: "text-rose-600" },
  violet: { icon: "bg-violet-50 text-violet-600", hint: "text-slate-500" },
  neutral: { icon: "bg-slate-100 text-slate-500", hint: "text-slate-500" },
};

interface KpiTileProps {
  label: string;
  /** `null` cuando la consulta falló o no hay permiso: se pinta un guion, no un cero inventado. */
  value: number | null;
  icon: ReactNode;
  tone?: KpiTone;
  hint?: string;
  onClick?: () => void;
}

/**
 * Un indicador del inicio: el icono en círculo, la etiqueta, la cifra grande y una línea de apoyo.
 * Es el mismo lenguaje que el tablero de CYC (`statement`), en modo plano.
 */
export default function KpiTile({ label, value, icon, tone = "neutral", hint, onClick }: KpiTileProps) {
  const style = TONES[tone];
  const Tag = onClick ? "button" : "div";

  return (
    <Tag
      {...(onClick ? { type: "button" as const, onClick } : {})}
      className={`flex w-full flex-col rounded-xl border border-slate-200 bg-white px-5 py-4 text-left transition ${
        onClick ? "cursor-pointer hover:border-slate-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-500/60" : ""
      }`}
    >
      <div className="flex w-full items-start gap-3">
        <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full ${style.icon}`}>{icon}</span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[12px] font-semibold text-slate-500">{label}</p>
          <p className="mt-1 truncate text-[26px] font-bold leading-none tabular-nums text-slate-900">
            {value === null ? "—" : value.toLocaleString("en-US")}
          </p>
        </div>
      </div>
      {hint && (
        <p className={`mt-3 truncate text-[12px] ${style.hint}`} title={hint}>
          {hint}
        </p>
      )}
    </Tag>
  );
}
