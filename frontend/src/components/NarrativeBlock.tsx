import {
  Brain,
  Layout,
  Activity,
  Shield,
  Search,
  CheckSquare
} from "lucide-react";

const sectionIcons: Record<string, any> = {
  Overview: Brain,
  "Structural Impact": Layout,
  "Behavioral Changes": Activity,
  "Risk Interpretation": Shield,
  "Review Focus": Search,
  Checklist: CheckSquare
};

export const NarrativeBlock: React.FC<{
  title: string;
  content: string;
  accentColor: string;
  isDarkMode: boolean;
}> = ({ title, content, accentColor, isDarkMode }) => {

  const Icon = sectionIcons[title];

  const textContent = isDarkMode
    ? "text-slate-300"
    : "text-slate-700";

  const cardBg = isDarkMode
    ? "bg-slate-800 border-slate-700"
    : "bg-slate-50 border-slate-200";

  const codeBg = isDarkMode
    ? "bg-slate-900 text-indigo-300 border-slate-700"
    : "bg-slate-100 text-indigo-700 border-slate-300";

  const lines = content.split("\n").filter(Boolean);

  return (
    <div className={`${cardBg} border rounded-lg p-5`}>

      {/* Title */}
      <div className="flex items-center gap-2 mb-3">

        {Icon && (
          <Icon
            size={16}
            className={isDarkMode ? "text-indigo-400" : "text-indigo-600"}
          />
        )}

        <h4 className={`text-sm font-semibold ${accentColor}`}>
          {title}
        </h4>

      </div>

      {/* Content */}
      <div className="space-y-2">

        {lines.map((line, i) => {

          const bullet = line.trim().startsWith("-");
          const clean = bullet ? line.replace("-", "").trim() : line;

          const parts = clean.split(/('.*?'|`.*?`)/g);

          return (
            <div key={i} className="flex gap-2 items-start">

              {bullet && (
                <span className="text-indigo-500 mt-[2px]">•</span>
              )}

              <p className={`text-sm ${textContent} leading-relaxed`}>

                {parts.map((p, idx) => {

                 if (
                    (p.startsWith("'") && p.endsWith("'")) ||
                    (p.startsWith("`") && p.endsWith("`"))
                    ){
                    return (
                      <code
                        key={idx}
                        className={`px-2 py-[2px] rounded-md text-xs font-mono border break-all ${codeBg}`}
                      >
                        {p.slice(1, -1)}
                      </code>
                    );
                  }

                  return p;

                })}

              </p>

            </div>
          );
        })}
      </div>

    </div>
  );
};