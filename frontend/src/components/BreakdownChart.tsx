import { BarChart3 } from "lucide-react";
import { InfoTooltip } from "./InfoTooltip";

const RISK_SIGNAL_META: Record<string, { label: string; tip: string; paper?: string }> = {
  averageRiskContribution: {
    label: "Avg File Risk",
    tip: "Mean risk score across all changed files. Weight 0.20. Each file maps to: LOW=0.15, MEDIUM=0.40, HIGH=0.70, CRITICAL=1.00.",
    paper: "Kim et al. (2008), IEEE TSE",
  },
  peakRiskContribution: {
    label: "Peak File Risk",
    tip: "Risk of the single highest-risk file. Weight 0.30 — heaviest signal because 80% of bugs come from 20% of files. One CRITICAL file dominates even if all others are LOW.",
    paper: "Kim et al. (2008) — 80/20 defect concentration rule",
  },
  criticalPathDensityContribution: {
    label: "Critical Path Density",
    tip: "Proportion of files on security, payment, DB, or config critical paths. Weight 0.20. Zero means no files triggered critical-path detection.",
  },
  highRiskDensityContribution: {
    label: "High Risk Density",
    tip: "Proportion of files rated HIGH or CRITICAL. Captures PRs where many files are individually risky, not just one outlier.",
  },
  complexityContribution: {
    label: "Complexity Δ",
    tip: "Contribution from net cyclomatic complexity increase. Weight 0.20. Capped at 200 effective units to prevent inflation from diff-line keyword counting. +1 CC ≈ +0.15 defects/KLOC.",
    paper: "Banker et al. (1993), MIS Quarterly",
  },
  testCoverageGapContribution: {
    label: "Test Coverage Gap",
    tip: "Proportion of production files changed without corresponding test changes. Weight 0.10. Gap=1.0 means zero test files modified. Untested changes have 2× post-merge defect rate.",
    paper: "Mockus & Votta (2000), ICSM",
  },
};

const DIFF_SIGNAL_META: Record<string, { label: string; tip: string; paper?: string }> = {
  cognitiveContribution: {
    label: "Cognitive Complexity",
    tip: "Primary driver (weight 0.35). Effective delta capped at 200 — raw 1296 was noise from diff-line keyword counting across 18 files. Real decision paths in a well-structured Java PR ≈ 50–100 units.",
    paper: "Campbell (2018), SonarSource; Bacchelli & Bird (2013)",
  },
  sizeContribution: {
    label: "Code Size (LOC Added)",
    tip: "Weight 0.25. Uses only linesAdded, NOT total churn (added+deleted). Deleted lines require verification, not comprehension. Pivot = 400 LOC per Rigby & Bird (2013).",
    paper: "Rigby & Bird (2013), FSE; SmartBear (2011)",
  },
  contextContribution: {
    label: "Arch. Context",
    tip: "Weight 0.20 (raised from 0.10 — was underweighted). Crosses architectural layers: business logic, infrastructure. Each layer requires a separate mental model.",
    paper: "Tamrawi et al. (2011), FSE; Bosu et al. (2015), MSR",
  },
  spreadContribution: {
    label: "File Spread",
    tip: "Weight 0.10. Number of files changed. Diminishing returns are steep — the 10th file adds little marginal difficulty beyond the 7th. Better captured by layer/domain signals.",
    paper: "Rigby & Bird (2013)",
  },
  criticalImpactContribution: {
    label: "Critical File Impact",
    tip: "Weight 0.10. Proportion of critical-path files in the PR. Zero here because no files scored ≥6 (the critical threshold requires ≥2 significant signals).",
  },
  concentrationContribution: {
    label: "Concentration",
    tip: "Whether changes are concentrated in a few heavy files or spread thin. High concentration = easier to focus review attention.",
  },
};

export const BreakdownChart: React.FC<{
  title: string;
  breakdown: Record<string, number>;
  type: 'risk' | 'difficulty';
  isDarkMode: boolean;
}> = ({ title, breakdown, type, isDarkMode }) => {
  const META = type === 'risk' ? RISK_SIGNAL_META : DIFF_SIGNAL_META;

  // Exclude raw* keys (display values, not signal contributions) and unknown keys
  const entries = Object.entries(breakdown)
    .filter(([key]) => !key.startsWith('raw') && META[key] !== undefined)
    .sort(([, a], [, b]) => b - a);

  const maxValue = Math.max(...entries.map(([, v]) => v), 0.0001);

  const bgClass      = isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200';
  const textPrimary  = isDarkMode ? 'text-white' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const barBg        = isDarkMode ? 'bg-slate-900' : 'bg-slate-200';
  const gradient     = type === 'risk'
    ? 'from-rose-500 to-orange-400'
    : 'from-violet-500 to-indigo-400';

  const chartTip = type === 'risk'
    ? "5-signal weighted formula: 0.30×peak + 0.20×avg + 0.20×complexity + 0.20×criticalDensity + 0.10×testGap. Bars are relative to the highest contributing signal."
    : "5-signal weighted formula: 0.35×cognitive + 0.25×size + 0.20×context + 0.10×spread + 0.10×critical. Cognitive delta capped at 200 to prevent heuristic inflation.";

  return (
    <div className={`${bgClass} border rounded-xl p-5`}>
      <h4 className={`text-sm font-semibold ${textPrimary} mb-4 flex items-center gap-2`}>
        <BarChart3 className={`w-4 h-4 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />
        {title}
        <InfoTooltip content={chartTip} isDarkMode={isDarkMode} />
      </h4>

      {entries.length === 0 ? (
        <p className={`text-xs ${textSecondary}`}>No signal data available.</p>
      ) : (
        <div className="space-y-3.5">
          {entries.map(([key, value]) => {
            const meta     = META[key];
            const barWidth = (value / maxValue) * 100;

            return (
              <div key={key}>
                <div className="flex items-center justify-between mb-1.5">
                  <span className={`text-xs font-medium flex items-center ${textSecondary}`}>
                    {meta.label}
                    <InfoTooltip
                      content={meta.tip}
                      paper={meta.paper}
                      isDarkMode={isDarkMode}
                    />
                  </span>
                  <span className={`text-xs font-mono font-semibold ${textPrimary}`}>
                    {(value * 100).toFixed(1)}%
                  </span>
                </div>
                <div className={`h-2 ${barBg} rounded-full overflow-hidden`}>
                  <div
                    className={`h-full rounded-full transition-all duration-500 bg-gradient-to-r ${gradient}`}
                    style={{ width: `${barWidth}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};