import { Brain } from "lucide-react";
import { formatDate } from "../services/utility";
import { NarrativeBlock } from "./NarrativeBlock";
import { AIGeneratedNarrative } from "../types";


export const NarrativeSection: React.FC<{ narrative: AIGeneratedNarrative; isDarkMode: boolean }> = ({ narrative, isDarkMode }) => {
  const bgClass = isDarkMode 
    ? 'bg-gradient-to-br from-slate-800/50 to-slate-900/50 border-slate-700/50' 
    : 'bg-gradient-to-br from-white to-slate-50 border-slate-200';
  
  const textPrimary = isDarkMode ? 'text-white' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-500' : 'text-slate-600';
  const textContent = isDarkMode ? 'text-slate-300' : 'text-slate-700';
  const accentColor = isDarkMode ? 'text-indigo-400' : 'text-indigo-600';
  const borderClass = isDarkMode ? 'border-slate-700/50' : 'border-slate-200';

  return (
    <div className={`${bgClass} border rounded-xl p-6 space-y-6`}>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`p-2 bg-gradient-to-br ${isDarkMode ? 'from-purple-500/20 to-pink-500/20 border-purple-500/30' : 'from-purple-100 to-pink-100 border-purple-300'} rounded-lg border`}>
            <Brain className={`w-5 h-5 ${isDarkMode ? 'text-purple-400' : 'text-purple-600'}`} />
          </div>
          <div>
            <h3 className={`text-lg font-bold ${textPrimary}`}>AI-Generated Analysis</h3>
            <p className={`text-xs ${textSecondary}`}>Confidence: {narrative.confidence}</p>
          </div>
        </div>
        <span className={`text-xs ${textSecondary}`}>{formatDate(narrative.generatedAt)}</span>
      </div>

      <div className="space-y-4">
        <NarrativeBlock title="Overview" content={narrative.overview} accentColor={accentColor} textContent={textContent} />
        <NarrativeBlock title="Structural Impact" content={narrative.structuralImpact} accentColor={accentColor} textContent={textContent} />
        <NarrativeBlock title="Behavioral Changes" content={narrative.behavioralChanges} accentColor={accentColor} textContent={textContent} />
        <NarrativeBlock title="Risk Interpretation" content={narrative.riskInterpretation} accentColor={accentColor} textContent={textContent} />
        <NarrativeBlock title="Review Focus" content={narrative.reviewFocus} accentColor={accentColor} textContent={textContent} />
        <NarrativeBlock title="Checklist" content={narrative.checklist} accentColor={accentColor} textContent={textContent} />
      </div>

      <div className={`pt-4 border-t ${borderClass}`}>
        <p className={`text-xs ${textSecondary} italic`}>{narrative.disclaimer}</p>
      </div>
    </div>
  );
};
