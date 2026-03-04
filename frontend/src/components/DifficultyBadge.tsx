import { DifficultyLevel } from "../types";
import { Clock } from "lucide-react";
import { getDifficultyColor } from "../services/utility";

export const DifficultyBadge: React.FC<{ level: DifficultyLevel; minutes: number; isDarkMode: boolean }> = ({ level, minutes, isDarkMode }) => {
  return (
    <div className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border ${getDifficultyColor(level, isDarkMode)}`}>
      <Clock className="w-4 h-4" />
      <span className="font-semibold text-sm">{level.replace('_', ' ')}</span>
      <span className="text-xs opacity-75">(~{minutes}m)</span>
    </div>
  );
};
