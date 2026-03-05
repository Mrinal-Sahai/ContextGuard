import { AlertTriangle, Flame } from "lucide-react";
import { RiskLevel } from "../types";
import { getRiskColor } from "../services/utility";
import { InfoTooltip } from "./InfoTooltip";

export const RiskLevelBadge: React.FC<{
  level: RiskLevel;
  score: number;
  isDarkMode: boolean;
  reviewerGuidance?: string;
  primaryDriver?: string;
}> = ({ level, score, isDarkMode, reviewerGuidance, primaryDriver }) => {
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const textMuted     = isDarkMode ? 'text-slate-500' : 'text-slate-400';
  const accentBorder  = isDarkMode ? 'border-rose-500/20' : 'border-rose-200';

  return (
    <div className="space-y-3">
      {/* Badge row */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border ${getRiskColor(level, isDarkMode)}`}>
          <AlertTriangle className="w-4 h-4" />
          <span className="font-semibold text-sm">{level}</span>
          <span className="text-xs opacity-75">({(score * 100).toFixed(1)}%)</span>
        </div>
        <InfoTooltip
          content="Weighted formula: 0.30×peakRisk + 0.20×avgRisk + 0.20×complexityDelta + 0.20×criticalDensity + 0.10×testCoverageGap. Peak risk carries the highest weight because 80% of bugs come from 20% of files."
          paper="Kim et al. (2008), IEEE TSE"
          isDarkMode={isDarkMode}
        />
      </div>

      {/* Primary driver callout */}
      {primaryDriver && (
        <div className={`flex items-start gap-2 text-xs ${textSecondary}`}>
          <Flame className="w-3.5 h-3.5 text-rose-400 mt-0.5 flex-shrink-0" />
          <span>
            <span className="font-semibold text-rose-400">Primary driver: </span>
            {primaryDriver}
          </span>
        </div>
      )}

      {/* Reviewer guidance */}
      {reviewerGuidance && (
        <p className={`text-xs leading-relaxed ${textSecondary} border-l-2 ${accentBorder} pl-2.5`}>
          {reviewerGuidance}
        </p>
      )}

      {/* Scale reference */}
      <div className={`text-xs ${textMuted}`}>
        LOW &lt;25% · MEDIUM &lt;50% · HIGH &lt;75% · CRITICAL ≥75%
      </div>
    </div>
  );
};