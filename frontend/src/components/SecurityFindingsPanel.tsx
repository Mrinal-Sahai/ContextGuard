import { useState } from "react";
import { ShieldAlert, ShieldCheck, ChevronDown, ChevronUp, FileCode } from "lucide-react";
import { SemgrepFinding } from "../types";

const SEVERITY_META = {
  ERROR:   { label: "HIGH",    bg: "bg-rose-500/10",   border: "border-rose-500/30",   text: "text-rose-400",   dot: "bg-rose-400" },
  WARNING: { label: "MEDIUM",  bg: "bg-amber-500/10",  border: "border-amber-500/30",  text: "text-amber-400",  dot: "bg-amber-400" },
  INFO:    { label: "INFO",    bg: "bg-blue-500/10",   border: "border-blue-500/30",   text: "text-blue-400",   dot: "bg-blue-400" },
};

function ruleShortName(ruleId: string): string {
  const parts = ruleId.split(".");
  return parts[parts.length - 1].replace(/-/g, " ");
}

function ruleCategory(ruleId: string): string {
  if (ruleId.startsWith("secret-detection.")) return "Secret / Credential";
  const parts = ruleId.split(".");
  return parts.length > 1 ? parts[0] : "SAST";
}

export default function SecurityFindingsPanel({
  findings,
  isDarkMode,
}: {
  findings: SemgrepFinding[];
  isDarkMode: boolean;
}) {
  const [expanded, setExpanded] = useState<number | null>(null);

  const cardBg    = isDarkMode ? "bg-slate-800/50 border-slate-700/50"   : "bg-white border-slate-200";
  const cellBg    = isDarkMode ? "bg-slate-900/50 border-slate-700/50"   : "bg-slate-50 border-slate-200";
  const textPrimary   = isDarkMode ? "text-slate-100" : "text-slate-900";
  const textSecondary = isDarkMode ? "text-slate-400" : "text-slate-600";
  const textMuted     = isDarkMode ? "text-slate-500" : "text-slate-400";

  const highCount = findings.filter(f => f.severity === "ERROR").length;
  const warnCount = findings.filter(f => f.severity === "WARNING").length;

  return (
    <div className={`border rounded-xl p-6 ${cardBg}`}>
      {/* Header */}
      <div className="flex items-center gap-2 mb-1 flex-wrap">
        <ShieldAlert className="w-5 h-5 text-rose-400" />
        <h3 className={`text-sm font-semibold uppercase tracking-wider ${textSecondary}`}>
          Security Findings
        </h3>
        <span className="ml-auto flex items-center gap-2">
          {highCount > 0 && (
            <span className="px-2 py-0.5 rounded-full text-xs font-bold bg-rose-500/15 text-rose-400 border border-rose-500/30">
              {highCount} HIGH
            </span>
          )}
          {warnCount > 0 && (
            <span className="px-2 py-0.5 rounded-full text-xs font-bold bg-amber-500/15 text-amber-400 border border-amber-500/30">
              {warnCount} MEDIUM
            </span>
          )}
        </span>
      </div>

      {highCount > 0 && (
        <p className={`text-xs mb-5 ${isDarkMode ? "text-rose-300" : "text-rose-600"}`}>
          {highCount} high-severity finding{highCount > 1 ? "s" : ""} detected — merge should be blocked until resolved.
        </p>
      )}
      {highCount === 0 && (
        <p className={`text-xs mb-5 ${textMuted}`}>
          {findings.length} finding{findings.length !== 1 ? "s" : ""} — review before merging.
        </p>
      )}

      {/* Finding rows */}
      <div className="space-y-2">
        {findings.map((f, i) => {
          const meta = SEVERITY_META[f.severity] ?? SEVERITY_META["WARNING"];
          const isOpen = expanded === i;
          return (
            <div key={i} className={`rounded-lg border ${meta.bg} ${meta.border}`}>
              <button
                onClick={() => setExpanded(isOpen ? null : i)}
                className="w-full flex items-center gap-3 px-4 py-3 text-left"
              >
                {/* Severity dot */}
                <span className={`w-2 h-2 rounded-full flex-shrink-0 ${meta.dot}`} />

                {/* Rule name */}
                <span className={`flex-1 min-w-0 text-xs font-semibold capitalize ${meta.text} truncate`}>
                  {ruleShortName(f.ruleId)}
                </span>

                {/* Category badge */}
                <span className={`hidden sm:inline text-xs px-1.5 py-0.5 rounded border ${meta.bg} ${meta.border} ${meta.text} flex-shrink-0`}>
                  {ruleCategory(f.ruleId)}
                </span>

                {/* File + line */}
                <span className={`text-xs font-mono ${textMuted} flex-shrink-0 flex items-center gap-1`}>
                  <FileCode className="w-3 h-3" />
                  {f.filePath.split("/").slice(-1)[0]}:{f.line}
                </span>

                {/* Severity label */}
                <span className={`text-xs font-bold ${meta.text} flex-shrink-0`}>{meta.label}</span>

                {isOpen
                  ? <ChevronUp className={`w-4 h-4 flex-shrink-0 ${meta.text}`} />
                  : <ChevronDown className={`w-4 h-4 flex-shrink-0 ${meta.text}`} />}
              </button>

              {isOpen && (
                <div className={`border-t px-4 py-3 space-y-2 ${meta.border}`}>
                  {/* Full file path */}
                  <div className="flex items-start gap-2">
                    <span className={`text-xs font-semibold w-16 flex-shrink-0 ${textSecondary}`}>File</span>
                    <span className={`text-xs font-mono ${textPrimary} break-all`}>{f.filePath}:{f.line}</span>
                  </div>
                  {/* Rule ID */}
                  <div className="flex items-start gap-2">
                    <span className={`text-xs font-semibold w-16 flex-shrink-0 ${textSecondary}`}>Rule</span>
                    <span className={`text-xs font-mono ${textMuted} break-all`}>{f.ruleId}</span>
                  </div>
                  {/* Message */}
                  <div className="flex items-start gap-2">
                    <span className={`text-xs font-semibold w-16 flex-shrink-0 ${textSecondary}`}>Detail</span>
                    <span className={`text-xs leading-relaxed ${textPrimary}`}>{f.message}</span>
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Footer note */}
      <div className={`mt-4 flex items-center gap-2 text-xs ${textMuted}`}>
        <ShieldCheck className="w-4 h-4 flex-shrink-0" />
        <span>
          Detected by ContextGuard secret scan (always runs) + Semgrep SAST (when installed).
          High-severity findings contribute to the HOLD verdict in the merge readiness banner.
        </span>
      </div>
    </div>
  );
}
