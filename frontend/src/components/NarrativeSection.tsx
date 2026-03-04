import { Brain } from "lucide-react";
import { formatDate } from "../services/utility";
import { NarrativeBlock } from "./NarrativeBlock";
import { AIGeneratedNarrative } from "../types";

export const NarrativeSection: React.FC<{
  narrative: AIGeneratedNarrative;
  isDarkMode: boolean;
}> = ({ narrative, isDarkMode }) => {

  const bgClass = isDarkMode
    ? "bg-slate-900 border-slate-700"
    : "bg-white border-slate-200";

  const textPrimary = isDarkMode ? "text-white" : "text-slate-900";
  const textSecondary = isDarkMode ? "text-slate-400" : "text-slate-600";
  const accentColor = isDarkMode ? "text-indigo-400" : "text-indigo-600";

  const borderClass = isDarkMode
    ? "border-slate-700"
    : "border-slate-200";

  return (
    <div className={`${bgClass} border rounded-xl p-6 w-full`}>

      {/* Header */}
      <div className="flex items-center justify-between mb-6">

        <div className="flex items-center gap-3">

          <div className="p-2 rounded-lg bg-purple-100 dark:bg-purple-900">
            <Brain className="w-5 h-5 text-purple-600 dark:text-purple-400" />
          </div>

          <div>

            <h3 className={`text-lg font-semibold ${textPrimary}`}>
              AI Analysis
            </h3>

            <p className={`text-xs ${textSecondary}`}>
              Confidence: <span className="font-medium">{narrative.confidence}</span>
            </p>

          </div>

        </div>

        <span className={`text-xs ${textSecondary}`}>
          {formatDate(narrative.generatedAt)}
        </span>

      </div>

      {/* Sections */}
      <div className="space-y-5 w-full">

        <NarrativeBlock
          title="Overview"
          content={narrative.overview}
          accentColor={accentColor}
          isDarkMode={isDarkMode}
        />

        <NarrativeBlock
          title="Structural Impact"
          content={narrative.structuralImpact}
          accentColor={accentColor}
          isDarkMode={isDarkMode}
        />

        <NarrativeBlock
          title="Behavioral Changes"
          content={narrative.behavioralChanges}
          accentColor={accentColor}
          isDarkMode={isDarkMode}
        />

        <NarrativeBlock
          title="Risk Interpretation"
          content={narrative.riskInterpretation}
          accentColor={accentColor}
          isDarkMode={isDarkMode}
        />

        <NarrativeBlock
          title="Review Focus"
          content={narrative.reviewFocus}
          accentColor={accentColor}
          isDarkMode={isDarkMode}
        />

        <NarrativeBlock
          title="Checklist"
          content={narrative.checklist}
          accentColor={accentColor}
          isDarkMode={isDarkMode}
        />

      </div>

      {/* Footer */}
      {narrative.disclaimer && (
        <div className={`pt-4 mt-6 border-t ${borderClass}`}>
          <p className={`text-xs italic ${textSecondary}`}>
            {narrative.disclaimer}
          </p>
        </div>
      )}

    </div>
  );
};