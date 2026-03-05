import { FileChangeSummary } from "../types";
import { useState } from "react";
import { FileCode, ChevronDown, ChevronRight, AlertCircle } from "lucide-react";
import { getRiskColor } from "../services/utility";
import { InfoTooltip } from "./InfoTooltip";

const BAND_COLORS_DARK: Record<string, string> = {
  LOW:      'bg-emerald-500/10 text-emerald-400 border-emerald-500/25',
  NOTABLE:  'bg-amber-500/10 text-amber-400 border-amber-500/25',
  CRITICAL: 'bg-rose-500/10 text-rose-400 border-rose-500/25',
  SEVERE:   'bg-red-500/15 text-red-300 border-red-500/30',
};
const BAND_COLORS_LIGHT: Record<string, string> = {
  LOW:      'bg-emerald-100 text-emerald-700 border-emerald-300',
  NOTABLE:  'bg-amber-100 text-amber-700 border-amber-300',
  CRITICAL: 'bg-rose-100 text-rose-700 border-rose-300',
  SEVERE:   'bg-red-100 text-red-700 border-red-300',
};

export const FileChangeItem: React.FC<{
  file: FileChangeSummary;
  isDarkMode: boolean;
}> = ({ file, isDarkMode }) => {
  const [expanded, setExpanded] = useState(false);

  const churn     = (file.linesAdded ?? 0) + (file.linesDeleted ?? 0);
  const highChurn = churn > 300;
  const band      = file.criticalDetectionResult?.criticalityBand ?? 'LOW';

  const bandColor = isDarkMode
    ? (BAND_COLORS_DARK[band] ?? BAND_COLORS_DARK.LOW)
    : (BAND_COLORS_LIGHT[band] ?? BAND_COLORS_LIGHT.LOW);

  const getChangeIcon = () => {
    if (file.changeType === 'added')   return <span className="text-emerald-400 font-bold text-xs ml-1">+</span>;
    if (file.changeType === 'deleted') return <span className="text-rose-400 font-bold text-xs ml-1">−</span>;
    return <span className="text-amber-400 font-bold text-xs ml-1">~</span>;
  };

  const getChangeColor = () => {
    if (isDarkMode) {
      if (file.changeType === 'added')   return 'border-emerald-500/30 bg-emerald-500/5';
      if (file.changeType === 'deleted') return 'border-rose-500/30 bg-rose-500/5';
      return 'border-amber-500/30 bg-amber-500/5';
    } else {
      if (file.changeType === 'added')   return 'border-emerald-300 bg-emerald-50';
      if (file.changeType === 'deleted') return 'border-rose-300 bg-rose-50';
      return 'border-amber-300 bg-amber-50';
    }
  };

  const textPrimary   = isDarkMode ? 'text-slate-200' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const hoverBg       = isDarkMode ? 'hover:bg-slate-800/30' : 'hover:bg-slate-100';
  const borderClass   = isDarkMode ? 'border-slate-700/50' : 'border-slate-200';
  const expandedBg    = isDarkMode ? 'bg-slate-900/30' : 'bg-slate-50';
  const cellBg        = isDarkMode ? 'bg-slate-900/50 border-slate-700/50' : 'bg-white border-slate-200';

  return (
    <div className={`border rounded-lg overflow-hidden ${getChangeColor()}`}>
      {/* Row header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className={`w-full px-4 py-3 flex items-center justify-between ${hoverBg} transition-colors text-left`}
      >
        <div className="flex items-center gap-3 flex-1 min-w-0">
          {expanded
            ? <ChevronDown className={`w-4 h-4 flex-shrink-0 ${textSecondary}`} />
            : <ChevronRight className={`w-4 h-4 flex-shrink-0 ${textSecondary}`} />}
          <FileCode className={`w-4 h-4 flex-shrink-0 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />
          <span className={`font-mono text-sm ${textPrimary} truncate`}>
            {file.filename.split('/').slice(-1)[0]}
          </span>
          <span className={`text-xs ${textSecondary} truncate hidden md:block`}>
            {file.filename.split('/').slice(0, -1).join('/')}
          </span>
          {getChangeIcon()}
        </div>

        <div className="flex items-center gap-2 ml-4 flex-shrink-0">
          {/* Criticality band badge */}
          <span className={`hidden sm:inline-flex px-2 py-0.5 rounded border text-xs font-semibold ${bandColor}`}>
            {band}
          </span>
          {/* Risk level badge */}
          <div className={`px-2 py-1 rounded text-xs font-medium ${getRiskColor(file.riskLevel, isDarkMode)}`}>
            {file.riskLevel}
          </div>
          {/* Line counts */}
          <div className={`flex items-center gap-1.5 text-xs ${textSecondary} font-mono`}>
            <span className="text-emerald-400">+{file.linesAdded}</span>
            <span className="text-rose-400">−{file.linesDeleted}</span>
          </div>
        </div>
      </button>

      {/* Expanded detail */}
      {expanded && (
        <div className={`px-4 pb-4 space-y-3 border-t ${borderClass} ${expandedBg}`}>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-3 pt-3">
            {/* Complexity delta */}
            <div className={`rounded-lg p-3 border ${cellBg}`}>
              <div className={`text-xs ${textSecondary} mb-1 flex items-center gap-0.5`}>
                Complexity Δ
                <InfoTooltip
                  content="Net cyclomatic complexity change in this file. +1 CC ≈ +0.15 defects/KLOC. Positive = more decision paths added."
                  paper="McCabe (1976); Banker et al. (1993)"
                  isDarkMode={isDarkMode}
                />
              </div>
              <div className={`text-sm font-semibold ${textPrimary}`}>
                {file.complexityDelta > 0 ? '+' : ''}{file.complexityDelta}
              </div>
            </div>

      
            {/* Total churn */}
            <div className={`rounded-lg p-3 border ${cellBg}`}>
              <div className={`text-xs ${textSecondary} mb-1 flex items-center gap-0.5`}>
                Total Churn
                <InfoTooltip
                  content={`Lines added + deleted = ${churn}. Threshold >300 LOC → 3× baseline defect rate. This file: ${highChurn ? 'ABOVE threshold ⚠' : 'below threshold ✓'}.`}
                  paper="Nagappan & Ball (2005), ICSE"
                  isDarkMode={isDarkMode}
                />
              </div>
              <div className={`text-sm font-semibold flex items-center gap-1.5 ${highChurn ? 'text-amber-400' : textPrimary}`}>
                {churn}
                {highChurn && <AlertCircle className="w-3.5 h-3.5 text-amber-400" />}
              </div>
            </div>

            {/* Detection score */}
            <div className={`rounded-lg p-3 border ${cellBg}`}>
              <div className={`text-xs ${textSecondary} mb-1 flex items-center gap-0.5`}>
                Detection Score
                <InfoTooltip
                  content="Sum of criticality signal scores. 0–2: LOW, 3–5: NOTABLE, 6–8: CRITICAL, 9+: SEVERE. Structural layer +2, high churn +3, security/payment +5, deletion +4."
                  paper="Nagappan & Ball (2005); Kim et al. (2008)"
                  isDarkMode={isDarkMode}
                />
              </div>
              <div className={`text-sm font-semibold ${textPrimary}`}>
                {file.criticalDetectionResult?.score ?? 0}
                <span className={`ml-1 text-xs ${textSecondary}`}>/ {band}</span>
              </div>
            </div>
          </div>

          {/* High-churn warning banner */}
          {highChurn && (
            <div className={`flex items-start gap-2 px-3 py-2.5 rounded-lg border ${
              isDarkMode ? 'border-amber-500/20 bg-amber-500/5' : 'border-amber-300 bg-amber-50'
            }`}>
              <AlertCircle className="w-3.5 h-3.5 text-amber-400 mt-0.5 flex-shrink-0" />
              <span className={`text-xs ${isDarkMode ? 'text-amber-300' : 'text-amber-700'}`}>
                {churn} lines churned exceeds the 300 LOC threshold. Files above this limit have 3× baseline
                defect rate (Nagappan & Ball, 2005). Pay extra attention to this file during review.
              </span>
            </div>
          )}

          {/* Detection signals */}
          {file.criticalDetectionResult?.reasons?.length > 0 && (
            <div>
              <div className={`text-xs font-semibold ${textSecondary} mb-2 uppercase tracking-wider`}>
                Detection Signals
              </div>
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