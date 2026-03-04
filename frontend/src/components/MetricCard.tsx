import { TrendingUp } from "lucide-react";

export const MetricCard: React.FC<{
  icon: React.ReactNode;
  label: string;
  value: string | number;
  description?: string;
  trend?: 'up' | 'down' | 'neutral';
  className?: string;
  isDarkMode: boolean;
}> = ({ icon, label, value, description, trend, className = '', isDarkMode }) => {
  const bgClass = isDarkMode 
    ? 'bg-slate-800/90 border-slate-700/50 hover:border-slate-600/50' 
    : 'bg-white border-slate-200 hover:border-slate-300';
  
  const textPrimary = isDarkMode ? 'text-white' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const textTertiary = isDarkMode ? 'text-slate-500' : 'text-slate-500';

  return (
    <div className={`relative group ${className}`}>
      <div className={`absolute inset-0 bg-gradient-to-br ${isDarkMode ? 'from-slate-700/50 to-slate-800/50' : 'from-slate-100 to-slate-200'} rounded-xl blur-xl opacity-0 group-hover:opacity-100 transition-opacity duration-500`} />
      <div className={`relative ${bgClass} backdrop-blur-sm border rounded-xl p-5 transition-all duration-300`}>
        <div className="flex items-start justify-between mb-3">
          <div className={`p-2.5 bg-gradient-to-br ${isDarkMode ? 'from-indigo-500/20 to-purple-500/20 border-indigo-500/30' : 'from-indigo-100 to-purple-100 border-indigo-300'} rounded-lg border`}>
            {icon}
          </div>
          {trend && (
            <TrendingUp className={`w-4 h-4 ${trend === 'up' ? 'text-emerald-400' : 'text-rose-400'}`} />
          )}
        </div>
        <div className="space-y-1">
          <div className={`text-2xl font-bold ${textPrimary}`}>{value}</div>
          <div className={`text-sm font-medium ${textSecondary}`}>{label}</div>
          {description && (
            <div className={`text-xs ${textTertiary} mt-2 pt-2 border-t ${isDarkMode ? 'border-slate-700/50' : 'border-slate-200'}`}>
              {description}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};