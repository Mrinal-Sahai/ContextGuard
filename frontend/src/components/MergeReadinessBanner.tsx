/**
 * MergeReadinessBanner.tsx
 *
 * Top-of-page verdict: combines risk + difficulty + SAST findings into one
 * clear, actionable recommendation.
 *
 * Three verdicts:
 *   HOLD     — do not merge until issues are addressed
 *   CAUTION  — merge with careful review, specific actions required
 *   READY    — standard review, no blocking signals
 *
 * Designed to raise questions and answer them in the same card.
 * The "why" section names the exact signals driving the verdict.
 */

import React from "react";
import {
  XCircle, AlertTriangle, CheckCircle2,
  Clock, Shield, Brain, Zap, GitMerge,
} from "lucide-react";

// ─── Types ────────────────────────────────────────────────────────────────────

interface RiskAssessment {
  overallScore?: number;
  level?: string;
  reviewerGuidance?: string;
  breakdown?: { rawSastFindings?: number };
}

interface DifficultyAssessment {
  overallScore?: number;
  level?: string;
  estimatedReviewMinutes?: number;
}

interface Props {
  risk?: RiskAssessment;
  difficulty?: DifficultyAssessment;
  semgrepFindingCount?: number;
  astAccurate?: boolean;
  isDarkMode: boolean;
}

// ─── Verdict logic ────────────────────────────────────────────────────────────

type Verdict = "HOLD" | "CAUTION" | "READY";

interface VerdictResult {
  verdict: Verdict;
  headline: string;
  subline: string;
  drivers: string[];
  actions: string[];
}

function computeVerdict(
  riskLevel?: string,
  difficultyLevel?: string,
  sastCount?: number,
): VerdictResult {
  const risk       = riskLevel?.toUpperCase()      ?? "LOW";
  const difficulty = difficultyLevel?.toUpperCase() ?? "TRIVIAL";
  const findings   = sastCount ?? 0;

  const drivers: string[]  = [];
  const actions: string[]  = [];

  // ── Collect drivers ───────────────────────────────────────────────────────
  if (risk === "CRITICAL")      drivers.push("CRITICAL risk score");
  if (risk === "HIGH")          drivers.push("HIGH risk score");
  if (difficulty === "VERY_HARD") drivers.push("VERY HARD review difficulty");
  if (difficulty === "HARD")    drivers.push("HARD review difficulty");
  if (findings >= 3)            drivers.push(`${findings} SAST security findings`);
  if (findings === 1 || findings === 2) drivers.push(`${findings} SAST finding${findings > 1 ? "s" : ""}`);

  // ── Determine verdict ─────────────────────────────────────────────────────
  const isHold =
    risk === "CRITICAL" ||
    (risk === "HIGH" && difficulty === "VERY_HARD") ||
    findings >= 3;

  const isCaution =
    !isHold && (
      risk === "HIGH" ||
      difficulty === "HARD" || difficulty === "VERY_HARD" ||
      findings >= 1
    );

  // ── Generate actions ──────────────────────────────────────────────────────
  if (risk === "CRITICAL" || risk === "HIGH") {
    actions.push("Require 2 reviewer approvals before merge");
    actions.push("Run integration tests locally against the highest-risk file");
  }
  if (difficulty === "HARD" || difficulty === "VERY_HARD") {
    actions.push("Assign a domain expert — this PR crosses architectural layers");
    actions.push("Block same-day merge; schedule a synchronous review session");
  }
  if (findings >= 1) {
    actions.push(`Address ${findings} SAST finding${findings > 1 ? "s" : ""} — see Static Analysis section`);
  }
  if (actions.length === 0) {
    actions.push("Standard async review is sufficient");
    actions.push("Any reviewer familiar with the module can approve");
  }

  if (isHold) {
    return {
      verdict: "HOLD",
      headline: "Hold — Do Not Merge",
      subline: "One or more blocking signals require resolution before this PR can safely land.",
      drivers,
      actions,
    };
  }
  if (isCaution) {
    return {
      verdict: "CAUTION",
      headline: "Review Carefully",
      subline: "No hard blockers, but elevated signals require focused attention from the right reviewer.",
      drivers,
      actions,
    };
  }
  return {
    verdict: "READY",
    headline: "Ready for Review",
    subline: "All signals are within normal bounds. Standard review process is sufficient.",
    drivers: drivers.length > 0 ? drivers : ["No blocking signals detected"],
    actions,
  };
}

// ─── Component ────────────────────────────────────────────────────────────────

const MergeReadinessBanner: React.FC<Props> = ({
  risk,
  difficulty,
  semgrepFindingCount,
  astAccurate,
  isDarkMode,
}) => {
  const { verdict, headline, subline, drivers, actions } = computeVerdict(
    risk?.level,
    difficulty?.level,
    semgrepFindingCount,
  );

  // ── Theme ─────────────────────────────────────────────────────────────────
  const themeMap: Record<Verdict, {
    border: string; bg: string; iconBg: string;
    icon: React.ReactNode; accentText: string; driverBg: string; driverText: string;
  }> = {
    HOLD: {
      border:      "border-rose-500/40",
      bg:          isDarkMode ? "bg-rose-950/30" : "bg-rose-50",
      iconBg:      "bg-rose-500/20",
      icon:        <XCircle className="w-7 h-7 text-rose-400" />,
      accentText:  "text-rose-400",
      driverBg:    isDarkMode ? "bg-rose-500/15 border-rose-500/30" : "bg-rose-100 border-rose-200",
      driverText:  isDarkMode ? "text-rose-300" : "text-rose-700",
    },
    CAUTION: {
      border:      "border-amber-500/40",
      bg:          isDarkMode ? "bg-amber-950/20" : "bg-amber-50",
      iconBg:      "bg-amber-500/20",
      icon:        <AlertTriangle className="w-7 h-7 text-amber-400" />,
      accentText:  "text-amber-400",
      driverBg:    isDarkMode ? "bg-amber-500/15 border-amber-500/30" : "bg-amber-100 border-amber-200",
      driverText:  isDarkMode ? "text-amber-300" : "text-amber-700",
    },
    READY: {
      border:      "border-emerald-500/30",
      bg:          isDarkMode ? "bg-emerald-950/20" : "bg-emerald-50",
      iconBg:      "bg-emerald-500/20",
      icon:        <CheckCircle2 className="w-7 h-7 text-emerald-400" />,
      accentText:  "text-emerald-400",
      driverBg:    isDarkMode ? "bg-emerald-500/15 border-emerald-500/30" : "bg-emerald-100 border-emerald-200",
      driverText:  isDarkMode ? "text-emerald-300" : "text-emerald-700",
    },
  };

  const t = themeMap[verdict];
  const textPrimary   = isDarkMode ? "text-slate-100" : "text-slate-900";
  const textSecondary = isDarkMode ? "text-slate-400"  : "text-slate-600";
  const cardBg        = isDarkMode ? "bg-slate-800/60 border-slate-700/50" : "bg-white border-slate-200";
  const divider       = isDarkMode ? "border-slate-700/50" : "border-slate-200";

  const riskScore  = risk?.overallScore  != null ? Math.round(risk.overallScore  * 100) : null;
  const diffScore  = difficulty?.overallScore != null ? Math.round(difficulty.overallScore * 100) : null;
  const reviewMins = difficulty?.estimatedReviewMinutes;

  const formatTime = (mins?: number) => {
    if (!mins) return "—";
    if (mins < 60) return `${mins} min`;
    const h = Math.floor(mins / 60);
    const m = mins % 60;
    return m === 0 ? `${h}h` : `${h}h ${m}m`;
  };

  return (
    <div className={`border rounded-xl overflow-hidden ${t.border} ${t.bg}`}>
      {/* ── Top row: verdict + stats ─────────────────────────────────────── */}
      <div className="p-5 flex flex-col sm:flex-row sm:items-center gap-4">
        {/* Icon + headline */}
        <div className="flex items-center gap-3 flex-1 min-w-0">
          <div className={`p-2 rounded-lg ${t.iconBg} shrink-0`}>
            {t.icon}
          </div>
          <div className="min-w-0">
            <div className={`text-lg font-black ${t.accentText}`}>{headline}</div>
            <div className={`text-sm mt-0.5 ${textSecondary}`}>{subline}</div>
          </div>
        </div>

        {/* Quick stats row */}
        <div className="flex items-center gap-3 flex-wrap shrink-0">
          {riskScore != null && (
            <Stat
              icon={<Shield className="w-3.5 h-3.5" />}
              label="Risk"
              value={`${riskScore}%`}
              accent={risk?.level}
              isDarkMode={isDarkMode}
            />
          )}
          {diffScore != null && (
            <Stat
              icon={<Brain className="w-3.5 h-3.5" />}
              label="Difficulty"
              value={`${diffScore}%`}
              accent={difficulty?.level}
              isDarkMode={isDarkMode}
            />
          )}
          {reviewMins != null && (
            <Stat
              icon={<Clock className="w-3.5 h-3.5" />}
              label="Est. review"
              value={formatTime(reviewMins)}
              isDarkMode={isDarkMode}
            />
          )}
          {semgrepFindingCount != null && semgrepFindingCount > 0 && (
            <Stat
              icon={<Zap className="w-3.5 h-3.5" />}
              label="SAST"
              value={`${semgrepFindingCount} finding${semgrepFindingCount > 1 ? "s" : ""}`}
              accent="HIGH"
              isDarkMode={isDarkMode}
            />
          )}
          {astAccurate && (
            <div className="px-2 py-1 rounded-md text-xs font-medium bg-cyan-500/15 text-cyan-400 border border-cyan-500/30">
              AST-backed
            </div>
          )}
        </div>
      </div>

      {/* ── Divider ─────────────────────────────────────────────────────────── */}
      <div className={`border-t ${divider}`} />

      {/* ── Drivers + Actions ───────────────────────────────────────────────── */}
      <div className="p-5 grid sm:grid-cols-2 gap-5">
        {/* Drivers */}
        <div>
          <div className={`text-xs font-semibold uppercase tracking-wider mb-2 ${textSecondary}`}>
            Why this verdict
          </div>
          <div className="flex flex-wrap gap-2">
            {drivers.map((d, i) => (
              <span
                key={i}
                className={`px-2.5 py-1 rounded-full text-xs font-medium border ${t.driverBg} ${t.driverText}`}
              >
                {d}
              </span>
            ))}
          </div>
        </div>

        {/* Actions */}
        <div>
          <div className={`text-xs font-semibold uppercase tracking-wider mb-2 ${textSecondary}`}>
            What to do
          </div>
          <ul className="space-y-1.5">
            {actions.map((a, i) => (
              <li key={i} className={`flex items-start gap-2 text-xs ${textSecondary}`}>
                <GitMerge className={`w-3.5 h-3.5 shrink-0 mt-0.5 ${t.accentText}`} />
                {a}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
};

// ─── Stat chip ────────────────────────────────────────────────────────────────

const accentColors: Record<string, string> = {
  LOW: "text-emerald-400", TRIVIAL: "text-emerald-400", EASY: "text-emerald-400",
  MEDIUM: "text-amber-400", MODERATE: "text-violet-400",
  HIGH: "text-orange-400",
  CRITICAL: "text-rose-400", VERY_HARD: "text-rose-400",
};

const Stat: React.FC<{
  icon: React.ReactNode; label: string; value: string;
  accent?: string; isDarkMode: boolean;
}> = ({ icon, label, value, accent, isDarkMode }) => {
  const color = accent ? (accentColors[accent.toUpperCase()] ?? "text-slate-300") : (isDarkMode ? "text-slate-300" : "text-slate-700");
  const bg    = isDarkMode ? "bg-slate-800/80 border-slate-700/50" : "bg-white border-slate-200";
  return (
    <div className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg border text-xs ${bg}`}>
      <span className={`${color} opacity-70`}>{icon}</span>
      <span className={isDarkMode ? "text-slate-400" : "text-slate-500"}>{label}</span>
      <span className={`font-bold ${color}`}>{value}</span>
    </div>
  );
};

export default MergeReadinessBanner;
