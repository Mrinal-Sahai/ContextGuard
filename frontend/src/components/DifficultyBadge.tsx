import { DifficultyLevel, DifficultyBreakdown } from "../types";
import { Clock, Brain } from "lucide-react";
import { getDifficultyColor } from "../services/utility";
import { InfoTooltip } from "./InfoTooltip";

export const DifficultyBadge: React.FC<{
  level: DifficultyLevel;
  minutes: number;
  isDarkMode: boolean;
  reviewerGuidance?: string;
  breakdown?: DifficultyBreakdown;
}> = ({ level, minutes, isDarkMode, reviewerGuidance, breakdown }) => {
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const textMuted     = isDarkMode ? 'text-slate-500' : 'text-slate-400';
  const accentBorder  = isDarkMode ? 'border-violet-500/20' : 'border-violet-200';

  // Build time breakdown tooltip from raw values when available
  const rawLOC    = breakdown?.rawLOC ?? 0;
  const rawCC     = Math.min(breakdown?.rawCognitiveDelta ?? 0, 200);
  const scanTime  = Math.round(12 * 1.5);
  const readTime  = Math.round((rawLOC / 100) * 1.2);
  const thinkTime = Math.round(rawCC * 0.5);

  const timeTooltip = breakdown
    ? `Additive time model:\n• Scan: ~${scanTime}m (prod files × 1.5m each)\n• Read: ~${readTime}m (${rawLOC.toLocaleString()} lines added × 1.2/100)\n• Think: ~${thinkTime}m (CC delta ${rawCC} × 0.5m)\n• × fatigue multiplier for ${level} level\n\nNote: uses linesAdded only — deleted lines need verification, not comprehension. CC delta capped at 200 to prevent heuristic inflation from diff-line keyword counting.`
    : `Estimated elapsed time for a thorough review at ${level} difficulty. Model: scan + read + think × fatigue multiplier.`;

  return (
    <div className="space-y-3">
      {/* Badge row */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border ${getDifficultyColor(level, isDarkMode)}`}>
          <Brain className="w-4 h-4" />
          <span className="font-semibold text-sm">{level.replace('_', ' ')}</span>
          <span className="text-xs opacity-75 flex items-center gap-1">
            <Clock className="w-3 h-3" />~{minutes}m
          </span>
        </div>
        <InfoTooltip
          content="Weighted formula: 0.35×cognitive + 0.25×size(linesAdded) + 0.20×archContext + 0.10×spread + 0.10×critical. Cognitive delta capped at 200 to prevent heuristic inflation."
          paper="Bacchelli & Bird (2013), ICSE; Rigby & Bird (2013), FSE"
          isDarkMode={isDarkMode}
        />
      </div>

      {/* Time breakdown link */}
      <div className={`flex items-center gap-1 text-xs ${textMuted}`}>
        <InfoTooltip
          content={timeTooltip}
          paper="SmartBear (2011); Rigby & Bird (2013)"
          isDarkMode={isDarkMode}
        />
        <span>Time estimate breakdown</span>
      </div>

      {/* Reviewer guidance */}
      {reviewerGuidance && (
        <p className={`text-xs leading-relaxed ${textSecondary} border-l-2 ${accentBorder} pl-2.5`}>
          {reviewerGuidance}
        </p>
      )}

      {/* Scale reference */}
      <div className={`text-xs ${textMuted}`}>
        TRIVIAL &lt;8m · EASY &lt;25m · MODERATE &lt;50m · HARD &lt;90m · VERY_HARD 90m+
      </div>
    </div>
  );
};