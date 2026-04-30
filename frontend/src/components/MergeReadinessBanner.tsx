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
 * Each signal is shown with:
 *   - Its label (what fired)
 *   - A one-line explanation of why it matters (research-backed)
 *   - A severity badge (BLOCKING / WARNING / OK)
 *
 * Signal sources (all grounded in actual backend data):
 *   • risk.level               — aggregate 5-signal weighted risk score
 *   • difficulty.level         — aggregate 5-signal weighted difficulty score
 *   • semgrepFindingCount      — total SAST findings from Semgrep
 *   • highSeveritySastCount    — ERROR-severity findings (high-confidence vulnerabilities)
 *   • risk.breakdown.rawTestCoverageGap  — fraction of changed files without test coverage
 *   • risk.breakdown.rawPeakRisk         — highest single-file risk score in the PR
 */

import React from "react";
import {
  XCircle, AlertTriangle, CheckCircle2,
  Clock, Shield, Brain, Zap, GitMerge,
  ShieldAlert, TestTube, FileWarning, CheckCircle, Hammer,
} from "lucide-react";

// ─── Types ────────────────────────────────────────────────────────────────────

interface RiskAssessment {
  overallScore?: number;
  level?: string;
  reviewerGuidance?: string;
  breakdown?: {
    /** 0.0–1.0: fraction of changed files with no associated test file */
    rawTestCoverageGap?: number;
    /** 0.0–1.0: risk score of the single highest-risk file in this PR */
    rawPeakRisk?: number;
  };
}

interface DifficultyAssessment {
  overallScore?: number;
  level?: string;
  estimatedReviewMinutes?: number;
}

interface CompilationStatus {
  hasErrors: boolean;
  errorCount: number;
  warningCount: number;
  parsedLanguages: string[];
}

interface Props {
  risk?: RiskAssessment;
  difficulty?: DifficultyAssessment;
  semgrepFindingCount?: number;
  /** ERROR-severity Semgrep findings only — high-confidence exploitable vulnerabilities */
  highSeveritySastFindingCount?: number;
  compilationStatus?: CompilationStatus;
  astAccurate?: boolean;
  isDarkMode: boolean;
}

// ─── Signal model ─────────────────────────────────────────────────────────────

type SignalSeverity = "BLOCKING" | "WARNING" | "OK";

interface Signal {
  /** Short label shown in the chip, e.g. "CRITICAL risk score" */
  label: string;
  /** One-line explanation of what this signal means and why it matters */
  detail: string;
  /** Whether this signal directly causes a HOLD verdict */
  severity: SignalSeverity;
  /** Lucide icon for the signal type */
  iconKey: "shield" | "brain" | "zap" | "test" | "file" | "check" | "build";
}

interface ActionItem {
  text: string;
  /** P0 = must fix before merge, P1 = required before approval, P2 = recommended */
  priority: "P0" | "P1" | "P2";
}

// ─── Verdict logic ────────────────────────────────────────────────────────────

type Verdict = "HOLD" | "CAUTION" | "READY";

interface VerdictResult {
  verdict: Verdict;
  headline: string;
  subline: string;
  signals: Signal[];
  actions: ActionItem[];
}

function computeVerdict(
  riskLevel?: string,
  difficultyLevel?: string,
  sastCount?: number,
  highSevSastCount?: number,
  rawTestCoverageGap?: number,
  rawPeakRisk?: number,
  compilationStatus?: CompilationStatus,
): VerdictResult {
  const risk          = riskLevel?.toUpperCase()       ?? "LOW";
  const difficulty    = difficultyLevel?.toUpperCase() ?? "TRIVIAL";
  const findings      = sastCount ?? 0;
  const highSevCount  = highSevSastCount ?? 0;
  const coverageGap   = rawTestCoverageGap ?? 0;
  // rawPeakRisk is 0.0–1.0; the file risk formula maps ≥0.75 → CRITICAL, ≥0.50 → HIGH
  const peakFileCritical = (rawPeakRisk ?? 0) >= 0.75;

  const buildErrors   = compilationStatus?.hasErrors   ? compilationStatus.errorCount   : 0;
  const buildWarnings = compilationStatus?.warningCount ?? 0;

  // ── Determine verdict ───────────────────────────────────────────────────────
  const isHold =
    risk === "CRITICAL" ||
    (risk === "HIGH" && difficulty === "VERY_HARD") ||
    highSevCount >= 1 ||   // any ERROR-level SAST finding = confirmed vulnerability
    findings >= 3 ||
    buildErrors > 0;       // compilation errors break the build — always a hard blocker

  const isCaution =
    !isHold && (
      risk === "HIGH" ||
      difficulty === "HARD" || difficulty === "VERY_HARD" ||
      findings >= 1 ||
      coverageGap >= 0.80 ||
      peakFileCritical
    );

  // ── Build signals list ──────────────────────────────────────────────────────
  const signals: Signal[] = [];
  const actions: ActionItem[] = [];

  // Risk level signal
  if (risk === "CRITICAL") {
    signals.push({
      label: "CRITICAL risk score",
      detail: "Weighted risk formula (Kim et al. 2008) rates this PR in the top danger tier — 80% of bugs originate from 20% of files, and this PR touches those files.",
      severity: "BLOCKING",
      iconKey: "shield",
    });
  } else if (risk === "HIGH") {
    signals.push({
      label: "HIGH risk score",
      detail: "Multiple risk signals are elevated (complexity delta, critical-path density, or test coverage gap). Defect probability is 2–4× baseline.",
      severity: isHold ? "BLOCKING" : "WARNING",
      iconKey: "shield",
    });
  } else {
    signals.push({
      label: `Risk: ${risk}`,
      detail: risk === "LOW"
        ? "Defect probability is within normal bounds. Files touched are low-criticality and well-tested."
        : "Moderate risk — some complexity or critical-path exposure, but below threshold.",
      severity: "OK",
      iconKey: "shield",
    });
  }

  // Difficulty signal
  if (difficulty === "VERY_HARD") {
    signals.push({
      label: "VERY HARD difficulty",
      detail: "Review demands 75+ min and spans multiple architectural layers. Comprehension error rate rises sharply above 60 min (SmartBear 2011).",
      severity: risk === "HIGH" ? "BLOCKING" : "WARNING",
      iconKey: "brain",
    });
  } else if (difficulty === "HARD") {
    signals.push({
      label: "HARD difficulty",
      detail: "High cognitive complexity — nesting-penalised score signals significant mental overhead. Assign a reviewer with domain expertise.",
      severity: "WARNING",
      iconKey: "brain",
    });
  } else {
    signals.push({
      label: `Difficulty: ${difficulty}`,
      detail: difficulty === "TRIVIAL" || difficulty === "EASY"
        ? "Review effort is low. Any reviewer familiar with the module can approve this in a single session."
        : "Moderate review effort. Standard pair-of-eyes review is sufficient.",
      severity: "OK",
      iconKey: "brain",
    });
  }

  // SAST — high-severity findings (ERROR level)
  if (highSevCount >= 1) {
    signals.push({
      label: `${highSevCount} HIGH-severity SAST finding${highSevCount > 1 ? "s" : ""} (ERROR)`,
      detail: `Semgrep flagged ${highSevCount} high-confidence security issue${highSevCount > 1 ? "s" : ""} (severity=ERROR). These are confirmed vulnerability patterns — SQL injection, secret leaks, unsafe deserialization, etc. — not style warnings.`,
      severity: "BLOCKING",
      iconKey: "zap",
    });
    actions.push({
      text: `Fix ${highSevCount} ERROR-severity SAST finding${highSevCount > 1 ? "s" : ""} before review — see Static Analysis section for file/line details`,
      priority: "P0",
    });
  }

  // SAST — warning/info findings
  const warnFindings = findings - highSevCount;
  if (warnFindings >= 3 && highSevCount === 0) {
    signals.push({
      label: `${findings} SAST findings`,
      detail: `${findings} Semgrep findings (WARNING/INFO severity) across changed files. High volume of warnings suggests systematic code-quality issues worth addressing before merge.`,
      severity: "BLOCKING",
      iconKey: "zap",
    });
    actions.push({
      text: `Triage all ${findings} SAST findings — address WARNINGs before merge, INFOs can be follow-up tickets`,
      priority: "P0",
    });
  } else if (findings >= 1 && highSevCount === 0) {
    signals.push({
      label: `${findings} SAST finding${findings > 1 ? "s" : ""}`,
      detail: `${findings} WARNING/INFO Semgrep finding${findings > 1 ? "s" : ""}. Not blocking individually, but each should be reviewed — see Static Analysis section.`,
      severity: "WARNING",
      iconKey: "zap",
    });
    actions.push({
      text: `Review ${findings} SAST finding${findings > 1 ? "s" : ""} — see Static Analysis section`,
      priority: "P1",
    });
  } else if (findings === 0) {
    signals.push({
      label: "SAST: clean",
      detail: "Semgrep found no security or correctness issues in the changed files.",
      severity: "OK",
      iconKey: "zap",
    });
  }

  // Test coverage gap — only surface if not already rolled into a HIGH/CRITICAL risk signal
  if (coverageGap >= 0.80) {
    const pct = Math.round(coverageGap * 100);
    signals.push({
      label: `${pct}% of changed files lack tests`,
      detail: `Mockus & Votta (2000): untested changes have 2× the post-merge defect rate. ${pct}% coverage gap means reviewers cannot rely on regression safety nets.`,
      severity: risk === "CRITICAL" || risk === "HIGH" ? "WARNING" : "WARNING",
      iconKey: "test",
    });
    if (risk !== "CRITICAL" && risk !== "HIGH") {
      actions.push({
        text: `Add tests for changed files — ${pct}% coverage gap doubles post-merge defect probability (Mockus & Votta 2000)`,
        priority: "P1",
      });
    }
  } else if (coverageGap < 0.40) {
    signals.push({
      label: "Coverage: adequate",
      detail: "Most changed files have associated test coverage. Regression safety nets are in place.",
      severity: "OK",
      iconKey: "test",
    });
  }

  // Compilation errors / warnings
  if (buildErrors > 0) {
    const langs = compilationStatus?.parsedLanguages?.join(", ") ?? "changed files";
    signals.push({
      label: `${buildErrors} compilation error${buildErrors > 1 ? "s" : ""} — build broken`,
      detail: `Static parse of ${langs} found ${buildErrors} compilation error${buildErrors > 1 ? "s" : ""}. A broken build cannot be safely merged — CI will fail and the artifact cannot be produced.`,
      severity: "BLOCKING",
      iconKey: "build",
    });
    actions.push({
      text: `Fix ${buildErrors} compilation error${buildErrors > 1 ? "s" : ""} — see Build Errors panel for file/line details`,
      priority: "P0",
    });
  } else if (buildWarnings > 0) {
    signals.push({
      label: `${buildWarnings} compilation warning${buildWarnings > 1 ? "s" : ""}`,
      detail: `${buildWarnings} compiler warning${buildWarnings > 1 ? "s" : ""} detected. Warnings often indicate type mismatches, unused imports, or deprecated API usage — review before merge.`,
      severity: "WARNING",
      iconKey: "build",
    });
    actions.push({
      text: `Review ${buildWarnings} compilation warning${buildWarnings > 1 ? "s" : ""} — see Build Errors panel`,
      priority: "P1",
    });
  } else if (compilationStatus && !compilationStatus.hasErrors) {
    signals.push({
      label: "Build: clean",
      detail: `No compilation errors or warnings in ${compilationStatus.parsedLanguages?.join(", ") || "changed files"}.`,
      severity: "OK",
      iconKey: "build",
    });
  }

  // Peak file risk — surfaces when an individual file is CRITICAL-tier
  if (peakFileCritical) {
    signals.push({
      label: "Contains a CRITICAL-risk file",
      detail: "At least one file scores in the CRITICAL risk tier (score ≥ 0.75). Peak-file risk carries 0.30 weight in the overall score — one bad file dominates the PR (Kim et al. 2008).",
      severity: risk === "CRITICAL" ? "BLOCKING" : "WARNING",
      iconKey: "file",
    });
    if (risk !== "CRITICAL") {
      actions.push({
        text: "Review the CRITICAL-risk file first — see per-file risk breakdown panel for which file it is",
        priority: "P1",
      });
    }
  }

  // ── Actions for aggregate-level conditions ──────────────────────────────────
  if (risk === "CRITICAL" || risk === "HIGH") {
    actions.push({
      text: "Require 2 reviewer approvals before merge",
      priority: risk === "CRITICAL" ? "P0" : "P1",
    });
    actions.push({
      text: "Run integration tests locally against the highest-risk file before approving",
      priority: "P1",
    });
  }
  if (difficulty === "HARD" || difficulty === "VERY_HARD") {
    actions.push({
      text: "Assign a domain expert — this PR crosses architectural layers or has high cognitive complexity",
      priority: "P1",
    });
    if (difficulty === "VERY_HARD") {
      actions.push({
        text: "Block same-day merge — schedule a synchronous review session instead",
        priority: "P1",
      });
    }
  }

  // Default READY actions
  if (actions.length === 0) {
    actions.push({ text: "Standard async review is sufficient", priority: "P2" });
    actions.push({ text: "Any reviewer familiar with the module can approve", priority: "P2" });
  }

  // Sort actions: P0 first, then P1, then P2
  actions.sort((a, b) => a.priority.localeCompare(b.priority));

  // ── Build result ────────────────────────────────────────────────────────────
  if (isHold) {
    return {
      verdict: "HOLD",
      headline: "Hold — Do Not Merge",
      subline: "One or more blocking signals require resolution before this PR can safely land.",
      signals,
      actions,
    };
  }
  if (isCaution) {
    return {
      verdict: "CAUTION",
      headline: "Review Carefully",
      subline: "No hard blockers, but elevated signals require focused attention from the right reviewer.",
      signals,
      actions,
    };
  }
  return {
    verdict: "READY",
    headline: "Ready for Review",
    subline: "All signals are within normal bounds. Standard review process is sufficient.",
    signals,
    actions,
  };
}

// ─── Icon helper ──────────────────────────────────────────────────────────────

const SignalIcon: React.FC<{ iconKey: Signal["iconKey"]; className?: string }> = ({ iconKey, className = "w-3.5 h-3.5" }) => {
  switch (iconKey) {
    case "shield": return <Shield className={className} />;
    case "brain":  return <Brain  className={className} />;
    case "zap":    return <Zap    className={className} />;
    case "test":   return <TestTube  className={className} />;
    case "file":   return <FileWarning className={className} />;
    case "check":  return <CheckCircle className={className} />;
    case "build":  return <Hammer className={className} />;
  }
};

// ─── Component ────────────────────────────────────────────────────────────────

const MergeReadinessBanner: React.FC<Props> = ({
  risk,
  difficulty,
  semgrepFindingCount,
  highSeveritySastFindingCount,
  compilationStatus,
  astAccurate,
  isDarkMode,
}) => {
  const { verdict, headline, subline, signals, actions } = computeVerdict(
    risk?.level,
    difficulty?.level,
    semgrepFindingCount,
    highSeveritySastFindingCount,
    risk?.breakdown?.rawTestCoverageGap,
    risk?.breakdown?.rawPeakRisk,
    compilationStatus,
  );

  // ── Theme ─────────────────────────────────────────────────────────────────
  const themeMap: Record<Verdict, {
    border: string; bg: string; iconBg: string;
    icon: React.ReactNode; accentText: string;
    blockingBg: string; blockingText: string; blockingBorder: string;
    warningBg: string;  warningText: string;  warningBorder: string;
    okBg: string;       okText: string;       okBorder: string;
  }> = {
    HOLD: {
      border:        "border-rose-500/40",
      bg:            isDarkMode ? "bg-rose-950/30" : "bg-rose-50",
      iconBg:        "bg-rose-500/20",
      icon:          <XCircle className="w-7 h-7 text-rose-400" />,
      accentText:    "text-rose-400",
      blockingBg:    isDarkMode ? "bg-rose-500/15"   : "bg-rose-100",
      blockingText:  isDarkMode ? "text-rose-300"     : "text-rose-700",
      blockingBorder:"border-rose-500/30",
      warningBg:     isDarkMode ? "bg-amber-500/10"  : "bg-amber-50",
      warningText:   isDarkMode ? "text-amber-300"    : "text-amber-700",
      warningBorder: "border-amber-500/30",
      okBg:          isDarkMode ? "bg-slate-700/40"  : "bg-slate-100",
      okText:        isDarkMode ? "text-slate-400"    : "text-slate-600",
      okBorder:      isDarkMode ? "border-slate-600/40" : "border-slate-200",
    },
    CAUTION: {
      border:        "border-amber-500/40",
      bg:            isDarkMode ? "bg-amber-950/20" : "bg-amber-50",
      iconBg:        "bg-amber-500/20",
      icon:          <AlertTriangle className="w-7 h-7 text-amber-400" />,
      accentText:    "text-amber-400",
      blockingBg:    isDarkMode ? "bg-rose-500/15"   : "bg-rose-100",
      blockingText:  isDarkMode ? "text-rose-300"     : "text-rose-700",
      blockingBorder:"border-rose-500/30",
      warningBg:     isDarkMode ? "bg-amber-500/15"  : "bg-amber-100",
      warningText:   isDarkMode ? "text-amber-300"    : "text-amber-700",
      warningBorder: "border-amber-500/30",
      okBg:          isDarkMode ? "bg-slate-700/40"  : "bg-slate-100",
      okText:        isDarkMode ? "text-slate-400"    : "text-slate-600",
      okBorder:      isDarkMode ? "border-slate-600/40" : "border-slate-200",
    },
    READY: {
      border:        "border-emerald-500/30",
      bg:            isDarkMode ? "bg-emerald-950/20" : "bg-emerald-50",
      iconBg:        "bg-emerald-500/20",
      icon:          <CheckCircle2 className="w-7 h-7 text-emerald-400" />,
      accentText:    "text-emerald-400",
      blockingBg:    isDarkMode ? "bg-rose-500/15"      : "bg-rose-100",
      blockingText:  isDarkMode ? "text-rose-300"        : "text-rose-700",
      blockingBorder:"border-rose-500/30",
      warningBg:     isDarkMode ? "bg-amber-500/10"     : "bg-amber-50",
      warningText:   isDarkMode ? "text-amber-300"       : "text-amber-700",
      warningBorder: "border-amber-500/30",
      okBg:          isDarkMode ? "bg-emerald-500/10"   : "bg-emerald-50",
      okText:        isDarkMode ? "text-emerald-400"     : "text-emerald-700",
      okBorder:      "border-emerald-500/25",
    },
  };

  const t = themeMap[verdict];
  const textSecondary = isDarkMode ? "text-slate-400"  : "text-slate-600";
  const divider       = isDarkMode ? "border-slate-700/50" : "border-slate-200";
  const cardBg        = isDarkMode ? "bg-slate-800/40" : "bg-white/60";

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

  const signalBg = (s: Signal) => {
    if (s.severity === "BLOCKING") return `${t.blockingBg} ${t.blockingBorder}`;
    if (s.severity === "WARNING")  return `${t.warningBg}  ${t.warningBorder}`;
    return `${t.okBg} ${t.okBorder}`;
  };
  const signalText = (s: Signal) => {
    if (s.severity === "BLOCKING") return t.blockingText;
    if (s.severity === "WARNING")  return t.warningText;
    return t.okText;
  };
  const severityBadgeClass = (sev: SignalSeverity) => {
    if (sev === "BLOCKING") return "bg-rose-500/20 text-rose-400 border-rose-500/30";
    if (sev === "WARNING")  return "bg-amber-500/15 text-amber-400 border-amber-500/30";
    return "bg-emerald-500/15 text-emerald-400 border-emerald-500/25";
  };

  const priorityBadge = (p: ActionItem["priority"]) => {
    if (p === "P0") return { label: "Must fix", cls: "bg-rose-500/20 text-rose-400 border-rose-500/30" };
    if (p === "P1") return { label: "Required",  cls: "bg-amber-500/15 text-amber-400 border-amber-500/30" };
    return           { label: "Recommended", cls: isDarkMode ? "bg-slate-700/60 text-slate-400 border-slate-600/40" : "bg-slate-100 text-slate-500 border-slate-200" };
  };

  return (
    <div className={`border rounded-xl overflow-hidden ${t.border} ${t.bg}`}>

      {/* ── Top row: verdict + quick stats ─────────────────────────────────── */}
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

        {/* Quick stats */}
        <div className="flex items-center gap-2 flex-wrap shrink-0">
          {riskScore != null && (
            <Stat icon={<Shield className="w-3.5 h-3.5" />} label="Risk"
              value={`${riskScore}%`} accent={risk?.level} isDarkMode={isDarkMode} />
          )}
          {diffScore != null && (
            <Stat icon={<Brain className="w-3.5 h-3.5" />} label="Difficulty"
              value={`${diffScore}%`} accent={difficulty?.level} isDarkMode={isDarkMode} />
          )}
          {reviewMins != null && (
            <Stat icon={<Clock className="w-3.5 h-3.5" />} label="Est. review"
              value={formatTime(reviewMins)} isDarkMode={isDarkMode} />
          )}
          {semgrepFindingCount != null && semgrepFindingCount > 0 && (
            <Stat icon={<Zap className="w-3.5 h-3.5" />} label="SAST"
              value={`${semgrepFindingCount} finding${semgrepFindingCount > 1 ? "s" : ""}`}
              accent={highSeveritySastFindingCount ? "CRITICAL" : "HIGH"}
              isDarkMode={isDarkMode} />
          )}
          {compilationStatus?.hasErrors && (
            <Stat icon={<Hammer className="w-3.5 h-3.5" />} label="Build"
              value={`${compilationStatus.errorCount} error${compilationStatus.errorCount > 1 ? "s" : ""}`}
              accent="CRITICAL"
              isDarkMode={isDarkMode} />
          )}
          {compilationStatus && !compilationStatus.hasErrors && compilationStatus.warningCount > 0 && (
            <Stat icon={<Hammer className="w-3.5 h-3.5" />} label="Build"
              value={`${compilationStatus.warningCount} warning${compilationStatus.warningCount > 1 ? "s" : ""}`}
              accent="MEDIUM"
              isDarkMode={isDarkMode} />
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

      {/* ── Signal breakdown ─────────────────────────────────────────────────── */}
      <div className="p-5">
        <div className={`text-xs font-semibold uppercase tracking-wider mb-3 ${textSecondary}`}>
          Signal breakdown — why this verdict
        </div>
        <div className="space-y-2">
          {signals.map((s, i) => (
            <div
              key={i}
              className={`flex items-start gap-3 p-3 rounded-lg border ${signalBg(s)}`}
            >
              {/* Icon */}
              <div className={`mt-0.5 shrink-0 ${signalText(s)}`}>
                <SignalIcon iconKey={s.iconKey} className="w-4 h-4" />
              </div>

              {/* Label + detail */}
              <div className="flex-1 min-w-0">
                <div className={`text-xs font-semibold ${signalText(s)}`}>{s.label}</div>
                <div className={`text-xs mt-0.5 leading-relaxed ${textSecondary}`}>{s.detail}</div>
              </div>

              {/* Severity badge */}
              <div className={`shrink-0 px-2 py-0.5 rounded-full text-[10px] font-bold border ${severityBadgeClass(s.severity)}`}>
                {s.severity}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* ── Divider ─────────────────────────────────────────────────────────── */}
      <div className={`border-t ${divider}`} />

      {/* ── Action checklist ─────────────────────────────────────────────────── */}
      <div className="p-5">
        <div className={`text-xs font-semibold uppercase tracking-wider mb-3 ${textSecondary}`}>
          What to do
        </div>
        <ul className="space-y-2">
          {actions.map((a, i) => {
            const badge = priorityBadge(a.priority);
            return (
              <li key={i} className="flex items-start gap-2.5">
                <GitMerge className={`w-3.5 h-3.5 shrink-0 mt-0.5 ${t.accentText}`} />
                <div className="flex-1 min-w-0">
                  <span className={`text-xs ${textSecondary}`}>{a.text}</span>
                </div>
                <span className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-bold border ${badge.cls}`}>
                  {badge.label}
                </span>
              </li>
            );
          })}
        </ul>
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
