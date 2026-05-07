import React from "react";
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

// Regex that matches inline code tokens in order of precedence:
// 1. **bold**              2. `backtick`           3. 'single-quoted'
// 4. PascalCase.method()  — ClassName.methodName() dotted call (matched as one chip)
// 5. camelCase()          — starts lowercase, has at least one uppercase hump; excludes ALL_CAPS
// 6. snake_case()         — contains underscore
// Lookbehind/lookahead on [/.] prevents splitting file paths (cadquery/occ_impl/foo.py)
const TOKEN_RE = /(\*\*(.+?)\*\*|`([^`]+)`|'([^']+)'|(?<![/.])\b([A-Z][a-zA-Z0-9]+\.[a-zA-Z][a-zA-Z0-9]*(?:\(\))?|[a-z][a-zA-Z0-9]*(?:[A-Z][a-zA-Z0-9]*)+(?:\(\))?|[a-zA-Z][a-zA-Z0-9]*(?:_[a-zA-Z0-9]+)+(?:\(\))?)(?![/.]))/g;

function renderInline(text: string, codeBg: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  TOKEN_RE.lastIndex = 0;

  while ((m = TOKEN_RE.exec(text)) !== null) {
    if (m.index > last) nodes.push(text.slice(last, m.index));

    if (m[2]) {
      // **bold**
      nodes.push(<strong key={m.index} className="font-semibold">{m[2]}</strong>);
    } else if (m[3]) {
      // `backtick`
      nodes.push(
        <code key={m.index} className={`px-1.5 py-0.5 rounded text-xs font-mono border ${codeBg}`}>
          {m[3]}
        </code>
      );
    } else if (m[4]) {
      // 'single-quoted'
      nodes.push(
        <code key={m.index} className={`px-1.5 py-0.5 rounded text-xs font-mono border ${codeBg}`}>
          {m[4]}
        </code>
      );
    } else if (m[5]) {
      // camelCase / snake_case / PascalCase identifier
      nodes.push(
        <code key={m.index} className={`px-1.5 py-0.5 rounded text-xs font-mono border ${codeBg}`}>
          {m[5]}
        </code>
      );
    }

    last = m.index + m[0].length;
  }

  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

export const NarrativeBlock: React.FC<{
  title: string;
  content: string;
  accentColor: string;
  isDarkMode: boolean;
}> = ({ title, content, accentColor, isDarkMode }) => {

  const Icon = sectionIcons[title];

  const textContent = isDarkMode ? "text-slate-300" : "text-slate-700";
  const cardBg      = isDarkMode ? "bg-slate-800 border-slate-700" : "bg-slate-50 border-slate-200";
  const codeBg      = isDarkMode
    ? "bg-slate-900 text-indigo-300 border-slate-700"
    : "bg-slate-100 text-indigo-700 border-slate-300";
  const numBg       = isDarkMode ? "text-indigo-400" : "text-indigo-600";

  const lines = content.split("\n").filter(l => l.trim().length > 0);

  return (
    <div className={`${cardBg} border rounded-lg p-5`}>

      {/* Title */}
      <div className="flex items-center gap-2 mb-3">
        {Icon && (
          <Icon size={16} className={isDarkMode ? "text-indigo-400" : "text-indigo-600"} />
        )}
        <h4 className={`text-sm font-semibold ${accentColor}`}>{title}</h4>
      </div>

      {/* Content */}
      <div className="space-y-2">
        {lines.map((line, i) => {
          const trimmed = line.trim();

          // Numbered list: "1. ", "2. ", etc.
          const numMatch = trimmed.match(/^(\d+)\.\s+(.*)/s);
          if (numMatch) {
            return (
              <div key={i} className="flex gap-2.5 items-start">
                <span className={`text-xs font-bold mt-0.5 min-w-4.5 ${numBg}`}>
                  {numMatch[1]}.
                </span>
                <p className={`text-sm ${textContent} leading-relaxed`}>
                  {renderInline(numMatch[2], codeBg)}
                </p>
              </div>
            );
          }

          // Bullet: "- " or "• "
          const bulletMatch = trimmed.match(/^[-•]\s+(.*)/s);
          if (bulletMatch) {
            return (
              <div key={i} className="flex gap-2 items-start">
                <span className="text-indigo-500 mt-0.75 leading-none">•</span>
                <p className={`text-sm ${textContent} leading-relaxed`}>
                  {renderInline(bulletMatch[1], codeBg)}
                </p>
              </div>
            );
          }

          // Plain paragraph
          return (
            <p key={i} className={`text-sm ${textContent} leading-relaxed`}>
              {renderInline(trimmed, codeBg)}
            </p>
          );
        })}
      </div>

    </div>
  );
};
