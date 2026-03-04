import { FileChangeSummary } from "../types";
import { useState } from "react";
import { FileCode, ChevronDown, ChevronRight } from "lucide-react";
import { getRiskColor } from "../services/utility";

export const FileChangeItem: React.FC<{ file: FileChangeSummary; isDarkMode: boolean }> = ({ file, isDarkMode }) => {
  const [expanded, setExpanded] = useState(false);

  const getChangeIcon = () => {
    if (file.changeType === 'added') return <span className="text-emerald-400">+</span>;
    if (file.changeType === 'deleted') return <span className="text-rose-400">-</span>;
    return <span className="text-amber-400">~</span>;
  };

  const getChangeColor = () => {
    if (isDarkMode) {
      if (file.changeType === 'added') return 'border-emerald-500/30 bg-emerald-500/5';
      if (file.changeType === 'deleted') return 'border-rose-500/30 bg-rose-500/5';
      return 'border-amber-500/30 bg-amber-500/5';
    } else {
      if (file.changeType === 'added') return 'border-emerald-300 bg-emerald-50';
      if (file.changeType === 'deleted') return 'border-rose-300 bg-rose-50';
      return 'border-amber-300 bg-amber-50';
    }
  };

  const textPrimary = isDarkMode ? 'text-slate-200' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const hoverBg = isDarkMode ? 'hover:bg-slate-800/30' : 'hover:bg-slate-100';
  const borderClass = isDarkMode ? 'border-slate-700/50' : 'border-slate-200';

  return (
    <div className={`border rounded-lg overflow-hidden ${getChangeColor()}`}>
      <button
        onClick={() => setExpanded(!expanded)}
        className={`w-full px-4 py-3 flex items-center justify-between ${hoverBg} transition-colors`}
      >
        <div className="flex items-center gap-3 flex-1 min-w-0">
          {expanded ? <ChevronDown className={`w-4 h-4 flex-shrink-0 ${textSecondary}`} /> : <ChevronRight className={`w-4 h-4 flex-shrink-0 ${textSecondary}`} />}
          <FileCode className={`w-4 h-4 flex-shrink-0 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />
          <span className={`font-mono text-sm ${textPrimary} truncate`}>{file.filename}</span>
          {getChangeIcon()}
        </div>
        <div className="flex items-center gap-4 ml-4">
          <div className={`px-2 py-1 rounded text-xs font-medium ${getRiskColor(file.riskLevel, isDarkMode)}`}>
            {file.riskLevel}
          </div>
          <div className={`flex items-center gap-2 text-xs ${textSecondary}`}>
            <span className="text-emerald-400">+{file.linesAdded}</span>
            <span className="text-rose-400">-{file.linesDeleted}</span>
          </div>
        </div>
      </button>

      {expanded && (
        <div className={`px-4 pb-4 space-y-3 border-t ${borderClass} ${isDarkMode ? 'bg-slate-900/30' : 'bg-slate-50'}`}>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 pt-3">
            <div>
              <div className={`text-xs ${textSecondary} mb-1`}>Complexity Δ</div>
              <div className={`text-sm font-semibold ${textPrimary}`}>{file.complexityDelta > 0 ? '+' : ''}{file.complexityDelta}</div>
            </div>
            <div>
              <div className={`text-xs ${textSecondary} mb-1`}>Before</div>
              <div className={`text-sm font-semibold ${textPrimary}`}>{file.totalComplexityBefore}</div>
            </div>
            <div>
              <div className={`text-xs ${textSecondary} mb-1`}>After</div>
              <div className={`text-sm font-semibold ${textPrimary}`}>{file.totalComplexityAfter}</div>
            </div>
            <div>
              <div className={`text-xs ${textSecondary} mb-1`}>Detection Score</div>
              <div className={`text-sm font-semibold ${textPrimary}`}>{file.criticalDetectionResult.score}</div>
            </div>
          </div>

          {file.criticalDetectionResult.reasons.length > 0 && (
            <div>
              <div className={`text-xs ${textSecondary} mb-2`}>Detection Signals</div>
              <div className="space-y-1">
                {file.criticalDetectionResult.reasons.map((reason, idx) => (
                  <div key={idx} className={`text-xs ${textSecondary} flex items-start gap-2`}>
                    <span className={isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}>•</span>
                    <span>{reason}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
