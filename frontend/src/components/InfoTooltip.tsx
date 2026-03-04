import { useState } from "react";
import { Info } from "lucide-react";

export const InfoTooltip: React.FC<{ content: string; isDarkMode: boolean }> = ({ content, isDarkMode }) => {
  const [show, setShow] = useState(false);
  const bgClass = isDarkMode ? 'bg-slate-900 border-slate-700' : 'bg-white border-slate-300';
  const textClass = isDarkMode ? 'text-slate-300' : 'text-slate-700';

  return (
    <div className="relative inline-block ml-1.5">
      <Info
        className={`w-4 h-4 ${isDarkMode ? 'text-slate-500 hover:text-indigo-400' : 'text-slate-400 hover:text-indigo-600'} cursor-help transition-colors`}
        onMouseEnter={() => setShow(true)}
        onMouseLeave={() => setShow(false)}
      />
      {show && (
        <div className={`absolute z-50 left-6 top-0 w-64 p-3 ${bgClass} border rounded-lg shadow-xl text-xs ${textClass}`}>
          {content}
          <div className={`absolute left-0 top-2 w-2 h-2 ${isDarkMode ? 'bg-slate-900 border-l border-t border-slate-700' : 'bg-white border-l border-t border-slate-300'} transform -translate-x-1 rotate-45`} />
        </div>
      )}
    </div>
  );
};