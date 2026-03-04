import { RiskLevel, DifficultyLevel } from "../types";


export const getRiskColor = (level: RiskLevel, isDark: boolean): string => {
  const colors = {
    LOW: isDark ? 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20' : 'text-emerald-700 bg-emerald-100 border-emerald-300',
    MEDIUM: isDark ? 'text-amber-400 bg-amber-500/10 border-amber-500/20' : 'text-amber-700 bg-amber-100 border-amber-300',
    HIGH: isDark ? 'text-orange-400 bg-orange-500/10 border-orange-500/20' : 'text-orange-700 bg-orange-100 border-orange-300',
    CRITICAL: isDark ? 'text-rose-400 bg-rose-500/10 border-rose-500/20' : 'text-rose-700 bg-rose-100 border-rose-300',
  };
  return colors[level];
};

export const getDifficultyColor = (level: DifficultyLevel, isDark: boolean): string => {
  const colors = {
    TRIVIAL: isDark ? 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20' : 'text-emerald-700 bg-emerald-100 border-emerald-300',
    EASY: isDark ? 'text-cyan-400 bg-cyan-500/10 border-cyan-500/20' : 'text-cyan-700 bg-cyan-100 border-cyan-300',
    MODERATE: isDark ? 'text-amber-400 bg-amber-500/10 border-amber-500/20' : 'text-amber-700 bg-amber-100 border-amber-300',
    HARD: isDark ? 'text-orange-400 bg-orange-500/10 border-orange-500/20' : 'text-orange-700 bg-orange-100 border-orange-300',
    VERY_HARD: isDark ? 'text-rose-400 bg-rose-500/10 border-rose-500/20' : 'text-rose-700 bg-rose-100 border-rose-300',
  };
  return colors[level];
};

export const formatDate = (dateStr: string): string => {
  return new Date(dateStr).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};