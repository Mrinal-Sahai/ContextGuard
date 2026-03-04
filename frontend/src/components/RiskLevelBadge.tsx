import { AlertTriangle } from "lucide-react";
import { RiskLevel } from "../types";
import { getRiskColor } from "../services/utility";

export const RiskLevelBadge: React.FC<{ level: RiskLevel; score: number; isDarkMode: boolean }> = ({ level, score, isDarkMode }) => {
  return (
    <div className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border ${getRiskColor(level, isDarkMode)}`}>
      <AlertTriangle className="w-4 h-4" />
      <span className="font-semibold text-sm">{level}</span>
      <span className="text-xs opacity-75">({(score * 100).toFixed(1)}%)</span>
    </div>
  );
};
