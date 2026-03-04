import { BarChart3 } from "lucide-react";

export const BreakdownChart: React.FC<{ 
  title: string; 
  breakdown: Record<string, number>; 
  type: 'risk' | 'difficulty';
  isDarkMode: boolean;
}> = ({ title, breakdown, type, isDarkMode }) => {
  const maxValue = Math.max(...Object.values(breakdown));
  
  const bgClass = isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200';
  const textPrimary = isDarkMode ? 'text-white' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const textTertiary = isDarkMode ? 'text-slate-300' : 'text-slate-700';
  const barBg = isDarkMode ? 'bg-slate-900' : 'bg-slate-200';
  
  return (
    <div className={`${bgClass} border rounded-xl p-5`}>
      <h4 className={`text-sm font-semibold ${textPrimary} mb-4 flex items-center gap-2`}>
        <BarChart3 className={`w-4 h-4 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />
        {title}
      </h4>
      <div className="space-y-3">
        {Object.entries(breakdown).map(([key, value]) => {
          const percentage = maxValue > 0 ? (value / maxValue) * 100 : 0;
          const label = key.replace(/([A-Z])/g, ' $1').replace(/^./, str => str.toUpperCase());
          
          return (
            <div key={key}>
              <div className="flex items-center justify-between mb-1.5">
                <span className={`text-xs ${textSecondary}`}>{label}</span>
                <span className={`text-xs font-mono ${textTertiary}`}>{(value * 100).toFixed(1)}%</span>
              </div>
              <div className={`h-2 ${barBg} rounded-full overflow-hidden`}>
                <div
                  className={`h-full rounded-full transition-all duration-500 ${
                    type === 'risk' ? 'bg-gradient-to-r from-rose-500 to-orange-500' : 'bg-gradient-to-r from-indigo-500 to-purple-500'
                  }`}
                  style={{ width: `${percentage}%` }}
                />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};