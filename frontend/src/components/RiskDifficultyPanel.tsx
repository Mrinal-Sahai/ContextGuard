/**
 * RiskDifficultyPanel.tsx
 *
 * Replaces the two-card RiskLevelBadge/DifficultyBadge row AND the two BreakdownChart
 * bar charts. Drop it in ReviewPage.tsx where those four blocks currently live:
 *
 *   REMOVE:
 *     {/* Risk + Difficulty *\/}   <div className="grid md:grid-cols-2 gap-6">...</div>
 *     {/* Breakdown Charts *\/}    <div className="grid md:grid-cols-2 gap-6">...</div>
 *
 *   ADD:
 *     <RiskDifficultyPanel
 *       risk={analysisData.risk}
 *       difficulty={analysisData.difficulty}
 *       isDarkMode={isDarkMode}
 *     />
 *
 * Props mirror the existing PRIntelligenceResponse shape — no backend changes needed.
 */

import React, { useState } from "react";
import {
  AlertTriangle,
  Brain,
  TrendingUp,
  FileSearch,
  FlaskConical,
  Layers,
  Files,
  Target,
  Clock,
  ChevronDown,
  ChevronUp,
  Info,
  Zap,
  ShieldAlert,
  BarChart2,
} from "lucide-react";

// ─── Types (inline subset — extend from your types/index.ts as needed) ────────

interface RiskBreakdown {
  averageRiskContribution?: number;
  peakRiskContribution?: number;
  criticalPathDensityContribution?: number;
  highRiskDensityContribution?: number;
  complexityContribution?: number;
  testCoverageGapContribution?: number;
  rawAverageRisk?: number;
  rawPeakRisk?: number;
  rawComplexityDelta?: number;
  rawCriticalDensity?: number;
  rawTestCoverageGap?: number;
}

interface DifficultyBreakdown {
  cognitiveContribution?: number;
  sizeContribution?: number;
  contextContribution?: number;
  spreadContribution?: number;
  criticalImpactContribution?: number;
  concentrationContribution?: number;
  rawCognitiveDelta?: number;
  rawLOC?: number;
  rawLayerCount?: number;
  rawDomainCount?: number;
  rawCriticalCount?: number;
}

interface RiskAssessment {
  overallScore?: number;
  level?: string;
  breakdown?: RiskBreakdown;
  reviewerGuidance?: string;
  criticalFilesDetected?: string[];
}

interface DifficultyAssessment {
  overallScore?: number;
  level?: string;
  breakdown?: DifficultyBreakdown;
  estimatedReviewMinutes?: number;
  reviewerGuidance?: string;
}

interface Props {
  risk?: RiskAssessment;
  difficulty?: DifficultyAssessment;
  isDarkMode: boolean;
}

// ─── Colour helpers ───────────────────────────────────────────────────────────

function riskColor(level?: string) {
  switch (level?.toUpperCase()) {
    case "LOW":      return { bg: "bg-emerald-500/15", text: "text-emerald-400", border: "border-emerald-500/30", fill: "#10b981" };
    case "MEDIUM":   return { bg: "bg-amber-500/15",   text: "text-amber-400",   border: "border-amber-500/30",   fill: "#f59e0b" };
    case "HIGH":     return { bg: "bg-orange-500/15",  text: "text-orange-400",  border: "border-orange-500/30",  fill: "#f97316" };
    case "CRITICAL": return { bg: "bg-rose-500/15",    text: "text-rose-400",    border: "border-rose-500/30",    fill: "#f43f5e" };
    default:         return { bg: "bg-slate-500/15",   text: "text-slate-400",   border: "border-slate-500/30",   fill: "#64748b" };
  }
}

function difficultyColor(level?: string) {
  switch (level?.toUpperCase()) {
    case "TRIVIAL":   return { bg: "bg-sky-500/15",     text: "text-sky-400",     border: "border-sky-500/30",     fill: "#0ea5e9" };
    case "EASY":      return { bg: "bg-emerald-500/15", text: "text-emerald-400", border: "border-emerald-500/30", fill: "#10b981" };
    case "MODERATE":  return { bg: "bg-violet-500/15",  text: "text-violet-400",  border: "border-violet-500/30",  fill: "#8b5cf6" };
    case "HARD":      return { bg: "bg-orange-500/15",  text: "text-orange-400",  border: "border-orange-500/30",  fill: "#f97316" };
    case "VERY_HARD": return { bg: "bg-rose-500/15",    text: "text-rose-400",    border: "border-rose-500/30",    fill: "#f43f5e" };
    default:          return { bg: "bg-slate-500/15",   text: "text-slate-400",   border: "border-slate-500/30",   fill: "#64748b" };
  }
}

/** Severity badge for a single signal's magnitude (0-1 contribution) */
function signalSeverity(contribution: number): { label: string; cls: string } {
  if (contribution >= 0.12) return { label: "High driver",   cls: "bg-rose-500/20 text-rose-400" };
  if (contribution >= 0.07) return { label: "Mid driver",    cls: "bg-amber-500/20 text-amber-400" };
  if (contribution >= 0.03) return { label: "Low driver",    cls: "bg-sky-500/20 text-sky-400" };
  return                           { label: "Negligible",    cls: "bg-slate-500/20 text-slate-400" };
}

// ─── SVG Gauge ────────────────────────────────────────────────────────────────

function ScoreGauge({ score, fill, size = 96 }: { score: number; fill: string; size?: number }) {
  const r = 36;
  const cx = size / 2;
  const cy = size / 2 + 6;
  const circumference = Math.PI * r;                  // half-circle
  const progress      = Math.min(Math.max(score, 0), 1);
  const dashOffset    = circumference * (1 - progress);

  return (
    <svg width={size} height={size * 0.65} viewBox={`0 0 ${size} ${size * 0.65}`}>
      {/* Track */}
      <path
        d={`M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}`}
        fill="none"
        stroke="currentColor"
        strokeWidth="7"
        className="text-slate-700"
        strokeLinecap="round"
      />
      {/* Progress */}
      <path
        d={`M ${cx - r} ${cy} A ${r} ${r} 0 0 1 ${cx + r} ${cy}`}
        fill="none"
        stroke={fill}
        strokeWidth="7"
        strokeLinecap="round"
        strokeDasharray={circumference}
        strokeDashoffset={dashOffset}
        style={{ transition: "stroke-dashoffset 1s ease" }}
      />
      {/* Score label */}
      <text
        x={cx}
        y={cy - 4}
        textAnchor="middle"
        fill="white"
        fontSize="15"
        fontWeight="700"
        fontFamily="monospace"
      >
        {(score * 100).toFixed(0)}
      </text>
      <text
        x={cx}
        y={cy + 10}
        textAnchor="middle"
        fill="#94a3b8"
        fontSize="8"
        fontFamily="sans-serif"
      >
        / 100
      </text>
    </svg>
  );
}

// ─── Signal Row ───────────────────────────────────────────────────────────────

interface SignalRowProps {
  icon: React.ReactNode;
  label: string;
  weight: string;
  contribution: number;  // weighted contribution (e.g. 0.12)
  rawLabel: string;      // e.g. "Peak file risk: 0.40"
  meaning: string;       // plain-English meaning of the raw value
  paper?: string;
  isDarkMode: boolean;
  accentFill: string;
}

function SignalRow({
  icon, label, weight, contribution, rawLabel, meaning, paper, isDarkMode, accentFill
}: SignalRowProps) {
  const [open, setOpen] = useState(false);
  const sev    = signalSeverity(contribution);
  const pct    = (contribution * 100).toFixed(1);
  const barPct = Math.min(contribution / 0.15 * 100, 100); // 0.15 = 100% bar width

  const rowBg    = isDarkMode ? "bg-slate-800/40 hover:bg-slate-800/70" : "bg-slate-50 hover:bg-slate-100";
  const border   = isDarkMode ? "border-slate-700/40" : "border-slate-200";
  const textSec  = isDarkMode ? "text-slate-400" : "text-slate-500";
  const textMain = isDarkMode ? "text-slate-100" : "text-slate-800";
  const trackBg  = isDarkMode ? "bg-slate-700" : "bg-slate-200";

  return (
    <div className={`rounded-lg border ${border} ${rowBg} transition-colors`}>
      <button
        className="w-full text-left px-4 py-3 flex items-center gap-3"
        onClick={() => setOpen(o => !o)}
      >
        {/* Icon */}
        <span className={`shrink-0 ${textSec}`}>{icon}</span>

        {/* Label + severity chip */}
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className={`text-sm font-semibold ${textMain}`}>{label}</span>
            <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded-full ${sev.cls}`}>
              {sev.label}
            </span>
            <span className={`text-[10px] ${textSec} ml-auto font-mono`}>weight {weight}</span>
          </div>

          {/* Mini progress bar */}
          <div className={`mt-1.5 h-1.5 ${trackBg} rounded-full overflow-hidden w-full`}>
            <div
              className="h-full rounded-full transition-all duration-700"
              style={{ width: `${barPct}%`, backgroundColor: accentFill }}
            />
          </div>
        </div>

        {/* Contribution score */}
        <div className="text-right shrink-0 ml-2">
          <span className={`text-sm font-bold font-mono ${textMain}`}>+{pct}%</span>
          <div className={`text-[10px] ${textSec}`}>of total</div>
        </div>

        {/* Chevron */}
        <span className={`shrink-0 ${textSec}`}>
          {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </span>
      </button>

      {/* Expanded detail */}
      {open && (
        <div className={`px-4 pb-3 border-t ${border} pt-3 space-y-2`}>
          <div className={`text-xs font-mono ${isDarkMode ? "text-slate-300" : "text-slate-700"}`}>
            {rawLabel}
          </div>
          <div className={`text-xs ${textSec} leading-relaxed`}>{meaning}</div>
          {paper && (
            <div className={`text-[11px] ${textSec} flex items-center gap-1`}>
              <Info size={10} /> Research: {paper}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ─── Formula strip ────────────────────────────────────────────────────────────

function FormulaStrip({
  terms, isDarkMode
}: {
  terms: { label: string; weight: string; fill: string }[];
  isDarkMode: boolean;
}) {
  const bg = isDarkMode ? "bg-slate-900/60 border-slate-700/40" : "bg-slate-100 border-slate-200";
  return (
    <div className={`rounded-lg border ${bg} px-4 py-3 flex flex-wrap items-center gap-2 text-xs font-mono`}>
      <span className="text-slate-400 mr-1">Score =</span>
      {terms.map((t, i) => (
        <React.Fragment key={t.label}>
          <span style={{ color: t.fill }} className="font-bold">{t.weight}</span>
          <span className={isDarkMode ? "text-slate-300" : "text-slate-600"}>× {t.label}</span>
          {i < terms.length - 1 && <span className="text-slate-500">+</span>}
        </React.Fragment>
      ))}
    </div>
  );
}

// ─── Main component ───────────────────────────────────────────────────────────

export const RiskDifficultyPanel: React.FC<Props> = ({ risk, difficulty, isDarkMode }) => {
  const rb = risk?.breakdown ?? {};
  const db = difficulty?.breakdown ?? {};

  const riskC  = riskColor(risk?.level);
  const diffC  = difficultyColor(difficulty?.level);

  const cardBg  = isDarkMode ? "bg-slate-800/50 border-slate-700/50" : "bg-white border-slate-200";
  const textMain = isDarkMode ? "text-slate-100" : "text-slate-900";
  const textSec  = isDarkMode ? "text-slate-400" : "text-slate-500";
  const divider  = isDarkMode ? "border-slate-700/50" : "border-slate-200";

  // ── Guidance banner interpretation ────────────────────────────────────────
  function GuidanceBanner({ text, fill }: { text?: string; fill: string }) {
    if (!text) return null;
    return (
      <div
        className="rounded-lg px-4 py-3 text-xs leading-relaxed border"
        style={{
          backgroundColor: `${fill}15`,
          borderColor: `${fill}30`,
          color: fill,
        }}
      >
        <span className="font-bold uppercase tracking-wide text-[10px] block mb-1 opacity-70">
          Reviewer Guidance
        </span>
        {text}
      </div>
    );
  }

  // ── Risk signals ───────────────────────────────────────────────────────────
  const riskSignals: Omit<SignalRowProps, "isDarkMode" | "accentFill">[] = [
    {
      icon: <TrendingUp size={15} />,
      label: "Peak File Risk",
      weight: "0.30",
      contribution: rb.peakRiskContribution ?? 0,
      rawLabel: `Raw peak: ${((rb.rawPeakRisk ?? 0) * 100).toFixed(0)} / 100  (mapped from file risk level)`,
      meaning: rb.rawPeakRisk === 1.0
        ? "At least one file is rated CRITICAL — the single highest-risk file dominates failure probability. 80% of post-merge bugs originate from 20% of files."
        : rb.rawPeakRisk! >= 0.7
        ? "Highest-risk file is rated HIGH. Deep line-by-line review required for that file."
        : rb.rawPeakRisk! >= 0.4
        ? "Highest-risk file is MEDIUM — warrants conscious attention but not alarm."
        : "No file exceeds LOW risk. Routine review is sufficient.",
      paper: "Kim et al. (2008) — 80/20 defect concentration rule, IEEE TSE",
    },
    {
      icon: <BarChart2 size={15} />,
      label: "Avg File Risk",
      weight: "0.20",
      contribution: rb.averageRiskContribution ?? 0,
      rawLabel: `Raw average: ${((rb.rawAverageRisk ?? 0) * 100).toFixed(0)} / 100  across all changed files`,
      meaning: `Mean file risk is ${((rb.rawAverageRisk ?? 0) * 100).toFixed(0)}/100. ${
        (rb.rawAverageRisk ?? 0) < 0.25
          ? "Most changed files are low-risk — the PR is broadly safe."
          : (rb.rawAverageRisk ?? 0) < 0.5
          ? "Several files carry moderate risk — ensure all are reviewed, not just the obvious ones."
          : "The majority of files are high-risk. Assign a senior reviewer."
      }`,
      paper: "Forsgren et al. (2018) — change failure rate vs mean file risk",
    },
    {
      icon: <FlaskConical size={15} />,
      label: "Complexity Δ",
      weight: "0.20",
      contribution: rb.complexityContribution ?? 0,
      rawLabel: `Raw CC delta: +${rb.rawComplexityDelta ?? 0} cyclomatic complexity units added`,
      meaning: `+${rb.rawComplexityDelta ?? 0} new decision paths added. ${
        (rb.rawComplexityDelta ?? 0) < 10
          ? "Modest increase — reviewer can trace branches quickly."
          : (rb.rawComplexityDelta ?? 0) < 30
          ? "Moderate increase — allocate extra time for branch-by-branch tracing."
          : "High increase — significant new branching logic. Each unit ≈ +0.15 defects/KLOC (Banker et al. 1993)."
      }`,
      paper: "Banker et al. (1993), MIS Quarterly — +1 CC ≈ +0.15 defects/KLOC",
    },
    {
      icon: <ShieldAlert size={15} />,
      label: "Critical Path Density",
      weight: "0.20",
      contribution: rb.criticalPathDensityContribution ?? 0,
      rawLabel: `${((rb.rawCriticalDensity ?? 0) * 100).toFixed(0)}% of changed files are on critical paths (auth / payments / DB / config)`,
      meaning: (rb.rawCriticalDensity ?? 0) === 0
        ? "No files triggered critical-path detection. No auth, payment, DB, or config files affected."
        : `${((rb.rawCriticalDensity ?? 0) * 100).toFixed(0)}% of files touch security-sensitive paths. Critical-path files have 3-4× the baseline defect rate.`,
      paper: "Nagappan & Ball (2005) — critical execution path defect rate",
    },
    {
      icon: <FileSearch size={15} />,
      label: "Test Coverage Gap",
      weight: "0.10",
      contribution: rb.testCoverageGapContribution ?? 0,
      rawLabel: `Gap: ${((rb.rawTestCoverageGap ?? 0) * 100).toFixed(0)}%  of production files changed without corresponding test changes`,
      meaning: (rb.rawTestCoverageGap ?? 0) >= 1.0
        ? "Zero test files modified alongside production changes. Changes without test coverage have 2× the post-merge bug rate."
        : (rb.rawTestCoverageGap ?? 0) > 0.5
        ? "More than half of production files lack test coverage in this PR. Consider adding tests before merging."
        : "Most production files have corresponding test changes. Good discipline.",
      paper: "Mockus & Votta (2000), ICSM — untested changes = 2× bug rate",
    },
  ];

  // ── Difficulty signals ─────────────────────────────────────────────────────
  const diffSignals: Omit<SignalRowProps, "isDarkMode" | "accentFill">[] = [
    {
      icon: <Brain size={15} />,
      label: "Cognitive Complexity",
      weight: "0.35",
      contribution: db.cognitiveContribution ?? 0,
      rawLabel: `Raw delta: +${db.rawCognitiveDelta ?? 0} cognitive complexity units`,
      meaning: `+${db.rawCognitiveDelta ?? 0} units of new mental branching for the reviewer to trace. ${
        (db.rawCognitiveDelta ?? 0) < 10
          ? "Low — simple changes, easy to follow."
          : (db.rawCognitiveDelta ?? 0) < 25
          ? "Moderate — plan for focused, uninterrupted review time."
          : "High — reviewer must trace many new code paths. Cognitive complexity is the #1 predictor of review comprehension time."
      }`,
      paper: "Campbell (2018), SonarSource; Bacchelli & Bird (2013), ICSE",
    },
    {
      icon: <Zap size={15} />,
      label: "Code Size (LOC Added)",
      weight: "0.25",
      contribution: db.sizeContribution ?? 0,
      rawLabel: `${db.rawLOC ?? 0} total lines changed  (pivot: 400 LOC = optimal PR boundary)`,
      meaning: `${db.rawLOC ?? 0} LOC is ${
        (db.rawLOC ?? 0) < 100
          ? "small — well within the optimal 400 LOC ceiling."
          : (db.rawLOC ?? 0) < 400
          ? "within the recommended range. Review thoroughness is high."
          : "above the recommended 400 LOC threshold — defect detection drops sharply beyond this. Consider splitting."
      }`,
      paper: "Rigby & Bird (2013), FSE — review effectiveness vs PR size",
    },
    {
      icon: <Layers size={15} />,
      label: "Architectural Context",
      weight: "0.20",
      contribution: db.contextContribution ?? 0,
      rawLabel: `${db.rawLayerCount ?? 0} architectural layers × ${db.rawDomainCount ?? 0} business domains touched`,
      meaning: `Crosses ${db.rawLayerCount ?? 0} layers${
        (db.rawLayerCount ?? 0) >= 3
          ? " (presentation → business → data-access). Reviewer must simultaneously hold multiple abstraction levels in mind."
          : (db.rawLayerCount ?? 0) === 2
          ? ". Layer crossing adds mental model maintenance overhead."
          : " — single-layer change is the easiest to review."
      }${(db.rawDomainCount ?? 0) > 0 ? ` + ${db.rawDomainCount} business domain(s) — each domain switch costs ~3-5 min.` : ""}`,
      paper: "Tamrawi et al. (2011), FSE; Bosu et al. (2015), MSR",
    },
    {
      icon: <Files size={15} />,
      label: "File Spread",
      weight: "0.10",
      contribution: db.spreadContribution ?? 0,
      rawLabel: `File spread signal (derived from total file count)`,
      meaning: `Captures context-switching cost across files. Diminishing returns past ~7 files — the 10th file adds little marginal review difficulty.`,
      paper: "Rigby & Bird (2013) — optimal PR ≤ 7 files",
    },
    {
      icon: <Target size={15} />,
      label: "Critical File Impact",
      weight: "0.10",
      contribution: db.criticalImpactContribution ?? 0,
      rawLabel: `${db.rawCriticalCount ?? 0} critical-path files in this PR`,
      meaning: (db.rawCriticalCount ?? 0) === 0
        ? "No critical-path files. Review focus can be routine."
        : `${db.rawCriticalCount} file(s) on critical paths require deep reading, not skimming. Critical files take 3× as long to review correctly.`,
      paper: "Nagappan & Ball (2005)",
    },
  ];

  const riskFormula = [
    { label: "Peak",       weight: "0.30", fill: riskC.fill  },
    { label: "Avg",        weight: "0.20", fill: "#f59e0b"   },
    { label: "Complexity", weight: "0.20", fill: "#a78bfa"   },
    { label: "CritPath",   weight: "0.20", fill: "#38bdf8"   },
    { label: "TestGap",    weight: "0.10", fill: "#fb923c"   },
  ];
  const diffFormula = [
    { label: "Cognitive",  weight: "0.35", fill: diffC.fill  },
    { label: "Size",       weight: "0.25", fill: "#34d399"   },
    { label: "Context",    weight: "0.20", fill: "#818cf8"   },
    { label: "Spread",     weight: "0.10", fill: "#f472b6"   },
    { label: "Critical",   weight: "0.10", fill: "#fb923c"   },
  ];

  return (
    <div className="grid md:grid-cols-2 gap-6">

      {/* ── RISK PANEL ───────────────────────────────────────────────────── */}
      <div className={`border rounded-xl overflow-hidden ${cardBg}`}>

        {/* Header row */}
        <div className={`px-6 pt-5 pb-4 border-b ${divider}`}>
          <div className="flex items-center gap-3">
            <div className={`p-2 rounded-lg ${riskC.bg} border ${riskC.border}`}>
              <AlertTriangle size={16} className={riskC.text} />
            </div>
            <div>
              <div className={`text-xs font-semibold uppercase tracking-widest ${textSec}`}>
                Risk Assessment
              </div>
              <div className={`text-base font-bold ${textMain}`}>
                Probability of post-merge defect
              </div>
            </div>

            {/* Score gauge top-right */}
            <div className="ml-auto flex flex-col items-center">
              <ScoreGauge
                score={risk?.overallScore ?? 0}
                fill={riskC.fill}
                size={80}
              />
              <span
                className={`text-xs font-bold uppercase tracking-wider mt-0.5 ${riskC.text}`}
              >
                {risk?.level ?? "—"}
              </span>
            </div>
          </div>

          {/* Score bar */}
          <div className="mt-4">
            <div className="flex justify-between text-[11px] mb-1">
              <span className={textSec}>0 — Low</span>
              <span className={textSec}>1.0 — Critical</span>
            </div>
            <div className="relative h-2.5 bg-slate-700 rounded-full overflow-hidden">
              {/* Threshold markers */}
              {[0.25, 0.50, 0.75].map(t => (
                <div
                  key={t}
                  className="absolute top-0 h-full w-px bg-slate-500/60"
                  style={{ left: `${t * 100}%` }}
                />
              ))}
              <div
                className="h-full rounded-full transition-all duration-1000"
                style={{
                  width: `${(risk?.overallScore ?? 0) * 100}%`,
                  backgroundColor: riskC.fill,
                }}
              />
              {/* Current position dot */}
              <div
                className="absolute top-1/2 -translate-y-1/2 w-3.5 h-3.5 rounded-full border-2 border-slate-900 shadow-lg transition-all duration-1000"
                style={{
                  left: `calc(${(risk?.overallScore ?? 0) * 100}% - 7px)`,
                  backgroundColor: riskC.fill,
                }}
              />
            </div>
            <div className="flex justify-between text-[10px] mt-1">
              {["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((l, i) => (
                <span
                  key={l}
                  className={`${
                    risk?.level?.toUpperCase() === l
                      ? `font-bold ${riskC.text}`
                      : textSec
                  }`}
                  style={{ width: "25%", textAlign: i === 0 ? "left" : i === 3 ? "right" : "center" }}
                >
                  {l}
                </span>
              ))}
            </div>
          </div>
        </div>

        {/* Formula */}
        <div className={`px-6 py-3 border-b ${divider}`}>
          <FormulaStrip terms={riskFormula} isDarkMode={isDarkMode} />
        </div>

        {/* Signals */}
        <div className="px-6 py-4 space-y-2">
          <div className={`text-[11px] font-semibold uppercase tracking-wider ${textSec} mb-3 flex items-center gap-1`}>
            <Info size={11} />
            Click any signal to see the raw value + what it means
          </div>
          {riskSignals.map(s => (
            <SignalRow
              key={s.label}
              {...s}
              isDarkMode={isDarkMode}
              accentFill={riskC.fill}
            />
          ))}
        </div>

        {/* Guidance */}
        {risk?.reviewerGuidance && (
          <div className={`px-6 pb-5 border-t ${divider} pt-4`}>
            <GuidanceBanner text={risk.reviewerGuidance} fill={riskC.fill} />
          </div>
        )}
      </div>

      {/* ── DIFFICULTY PANEL ─────────────────────────────────────────────── */}
      <div className={`border rounded-xl overflow-hidden ${cardBg}`}>

        {/* Header row */}
        <div className={`px-6 pt-5 pb-4 border-b ${divider}`}>
          <div className="flex items-center gap-3">
            <div className={`p-2 rounded-lg ${diffC.bg} border ${diffC.border}`}>
              <Brain size={16} className={diffC.text} />
            </div>
            <div>
              <div className={`text-xs font-semibold uppercase tracking-widest ${textSec}`}>
                Difficulty Assessment
              </div>
              <div className={`text-base font-bold ${textMain}`}>
                Cognitive effort to review correctly
              </div>
            </div>

            {/* Score gauge */}
            <div className="ml-auto flex flex-col items-center">
              <ScoreGauge
                score={difficulty?.overallScore ?? 0}
                fill={diffC.fill}
                size={80}
              />
              <span className={`text-xs font-bold uppercase tracking-wider mt-0.5 ${diffC.text}`}>
                {difficulty?.level ?? "—"}
              </span>
            </div>
          </div>

          {/* Estimated time callout */}
          {difficulty?.estimatedReviewMinutes != null && (
            <div
              className={`mt-4 flex items-center gap-3 rounded-lg px-4 py-3 border`}
              style={{
                backgroundColor: `${diffC.fill}12`,
                borderColor: `${diffC.fill}30`,
              }}
            >
              <Clock size={18} style={{ color: diffC.fill }} />
              <div>
                <div className="text-xs font-semibold uppercase tracking-wider" style={{ color: diffC.fill }}>
                  Estimated Review Time
                </div>
                <div className={`text-xl font-black ${textMain}`}>
                  {difficulty.estimatedReviewMinutes} min
                  <span className={`text-sm font-normal ml-2 ${textSec}`}>
                    {difficulty.estimatedReviewMinutes < 15
                      ? "Quick — async approval fine"
                      : difficulty.estimatedReviewMinutes < 30
                      ? "Moderate — same-day, focused block"
                      : difficulty.estimatedReviewMinutes < 60
                      ? "Significant — book a 1-hour slot"
                      : "Extensive — consider splitting PR"}
                  </span>
                </div>
              </div>
            </div>
          )}

          {/* Score bar */}
          <div className="mt-4">
            <div className="flex justify-between text-[11px] mb-1">
              <span className={textSec}>0 — Trivial</span>
              <span className={textSec}>1.0 — Very Hard</span>
            </div>
            <div className="relative h-2.5 bg-slate-700 rounded-full overflow-hidden">
              {[0.15, 0.35, 0.55, 0.75].map(t => (
                <div
                  key={t}
                  className="absolute top-0 h-full w-px bg-slate-500/60"
                  style={{ left: `${t * 100}%` }}
                />
              ))}
              <div
                className="h-full rounded-full transition-all duration-1000"
                style={{
                  width: `${(difficulty?.overallScore ?? 0) * 100}%`,
                  backgroundColor: diffC.fill,
                }}
              />
              <div
                className="absolute top-1/2 -translate-y-1/2 w-3.5 h-3.5 rounded-full border-2 border-slate-900 shadow-lg transition-all duration-1000"
                style={{
                  left: `calc(${(difficulty?.overallScore ?? 0) * 100}% - 7px)`,
                  backgroundColor: diffC.fill,
                }}
              />
            </div>
            <div className="flex justify-between text-[10px] mt-1">
              {["TRIVIAL", "EASY", "MODERATE", "HARD", "VERY HARD"].map((l, i) => (
                <span
                  key={l}
                  className={`${
                    difficulty?.level?.toUpperCase() === l.replace(" ", "_")
                      ? `font-bold ${diffC.text}`
                      : textSec
                  }`}
                  style={{ width: "20%", textAlign: i === 0 ? "left" : i === 4 ? "right" : "center" }}
                >
                  {l}
                </span>
              ))}
            </div>
          </div>
        </div>

        {/* Formula */}
        <div className={`px-6 py-3 border-b ${divider}`}>
          <FormulaStrip terms={diffFormula} isDarkMode={isDarkMode} />
        </div>

        {/* Signals */}
        <div className="px-6 py-4 space-y-2">
          <div className={`text-[11px] font-semibold uppercase tracking-wider ${textSec} mb-3 flex items-center gap-1`}>
            <Info size={11} />
            Click any signal to see the raw value + what it means
          </div>
          {diffSignals.map(s => (
            <SignalRow
              key={s.label}
              {...s}
              isDarkMode={isDarkMode}
              accentFill={diffC.fill}
            />
          ))}
        </div>

        {/* Guidance */}
        {difficulty?.reviewerGuidance && (
          <div className={`px-6 pb-5 border-t ${divider} pt-4`}>
            <GuidanceBanner text={difficulty.reviewerGuidance} fill={diffC.fill} />
          </div>
        )}
      </div>
    </div>
  );
};

export default RiskDifficultyPanel;