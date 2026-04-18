import { Cpu, Zap, AlertCircle, Minus, Plus } from "lucide-react";
import { DiffMetrics } from "../types";
import { InfoTooltip } from "./InfoTooltip";

export const ASTMetricsPanel: React.FC<{
  metrics: DiffMetrics;
  isDarkMode: boolean;
}> = ({ metrics, isDarkMode }) => {
  const {
    complexityDelta,
    avgChangedMethodCC,
    maxCallDepth,
    removedPublicMethods,
    addedPublicMethods,
    hotspotMethodIds,
    astAccurate,
    semgrepFindingCount,
  } = metrics;

  const removedCount = removedPublicMethods ?? 0;
  const addedCount   = addedPublicMethods   ?? 0;

  const textPrimary   = isDarkMode ? 'text-slate-100' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const textMuted     = isDarkMode ? 'text-slate-500' : 'text-slate-400';
  const cardBg        = isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200';
  const cellBg        = isDarkMode ? 'bg-slate-900/50 border-slate-700/50' : 'bg-slate-50 border-slate-200';

  // Interpretation helpers
  const ccLabel = !avgChangedMethodCC ? '—'
    : avgChangedMethodCC < 4 ? 'simple'
    : avgChangedMethodCC < 8 ? 'moderate'
    : 'high — trace all branches';
  const ccColor = !avgChangedMethodCC ? textPrimary
    : avgChangedMethodCC < 4 ? 'text-emerald-400'
    : avgChangedMethodCC < 8 ? 'text-amber-400'
    : 'text-rose-400';

  const depthLabel = !maxCallDepth ? '—'
    : maxCallDepth <= 2 ? 'shallow'
    : maxCallDepth <= 4 ? 'medium'
    : maxCallDepth <= 6 ? 'deep'
    : 'very deep';
  const depthColor = !maxCallDepth ? textPrimary
    : maxCallDepth <= 2 ? 'text-emerald-400'
    : maxCallDepth <= 4 ? 'text-amber-400'
    : 'text-rose-400';

  const deltaColor = !complexityDelta ? textPrimary
    : complexityDelta < 0 ? 'text-emerald-400'
    : complexityDelta === 0 ? textPrimary
    : 'text-amber-400';

  const shortMethod = (id: string) => id.split('.').slice(-2).join('.');
  const methodPkg   = (id: string) => id.split('.').slice(0, -2).join('.');

  return (
    <div className={`${cardBg} border rounded-xl p-6`}>
      {/* Header */}
      <div className="flex items-center gap-2 mb-5 flex-wrap">
        <Cpu className={`w-5 h-5 ${isDarkMode ? 'text-cyan-400' : 'text-cyan-600'}`} />
        <h3 className={`text-sm font-semibold ${textSecondary} uppercase tracking-wider`}>
          AST-Derived Metrics
        </h3>
        <InfoTooltip
          content="Computed from Java AST parsing (JavaParser), not diff-line heuristics. Available after FlowExtractorService completes and feeds back into risk and difficulty scores."
          isDarkMode={isDarkMode}
        />
        {/* AST accuracy badge */}
        {astAccurate === true ? (
          <span className="ml-auto px-2 py-0.5 rounded-full text-xs font-semibold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
            AST-backed ✓
          </span>
        ) : (
          <span className="ml-auto px-2 py-0.5 rounded-full text-xs font-semibold bg-amber-500/15 text-amber-400 border border-amber-500/30">
            Heuristic estimate
          </span>
        )}
        {/* Semgrep finding badge */}
        {semgrepFindingCount != null && semgrepFindingCount > 0 && (
          <span className="px-2 py-0.5 rounded-full text-xs font-semibold bg-rose-500/15 text-rose-400 border border-rose-500/30">
            {semgrepFindingCount} Semgrep {semgrepFindingCount === 1 ? 'finding' : 'findings'}
          </span>
        )}
      </div>

      {/* 4-cell grid */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-5">
        {/* Complexity delta */}
        <div className={`rounded-lg p-4 border ${cellBg}`}>
          <div className={`text-xs ${textSecondary} mb-2 flex items-center gap-0.5`}>
            Complexity Δ
            <InfoTooltip
              content="Net cyclomatic complexity change across all changed methods. Negative = PR simplifies code. Each CC unit = one additional path to mentally trace. +1 CC ≈ +0.15 defects/KLOC."
              paper="McCabe (1976), IEEE TSE; Banker et al. (1993)"
              isDarkMode={isDarkMode}
            />
          </div>
          <div className={`text-2xl font-black tabular-nums ${deltaColor}`}>
            {(complexityDelta ?? 0) >= 0 ? '+' : ''}{(complexityDelta ?? 0).toLocaleString()}
          </div>
          <div className={`text-xs mt-1 ${textMuted}`}>
            {!complexityDelta ? 'not available'
              : complexityDelta < 0 ? 'simplification ✓'
              : complexityDelta === 0 ? 'neutral'
              : 'complexity added'}
          </div>
        </div>

        {/* Avg method CC */}
        <div className={`rounded-lg p-4 border ${cellBg}`}>
          <div className={`text-xs ${textSecondary} mb-2 flex items-center gap-0.5`}>
            Avg Method CC
            <InfoTooltip
              content="Average cyclomatic complexity of methods actually changed in this PR. Unlike total delta, this shows per-method complexity — more useful for reviewer burden. SonarQube refactoring threshold = CC ≥ 10."
              paper="McCabe (1976); Campbell (2018)"
              isDarkMode={isDarkMode}
            />
          </div>
          <div className={`text-2xl font-black tabular-nums ${ccColor}`}>
            {avgChangedMethodCC?.toFixed(2) ?? '—'}
          </div>
          <div className={`text-xs mt-1 ${textMuted}`}>{ccLabel}</div>
        </div>

        {/* Max call depth */}
        <div className={`rounded-lg p-4 border ${cellBg}`}>
          <div className={`text-xs ${textSecondary} mb-2 flex items-center gap-0.5`}>
            Max Call Depth
            <InfoTooltip
              content="Longest call chain introduced by this PR, measured in method boundaries (hops). Computed via BFS on added AST call graph edges. Each hop = one method boundary to mentally model. Depth >5 correlates with significantly increased debugging time."
              paper="Landman et al. (2016), ICSE; Briand et al. (1999)"
              isDarkMode={isDarkMode}
            />
          </div>
          <div className={`text-2xl font-black tabular-nums ${depthColor}`}>
            {maxCallDepth ?? '—'}
          </div>
          <div className={`text-xs mt-1 ${textMuted}`}>{depthLabel}</div>
        </div>

        {/* Public API changes */}
        <div className={`rounded-lg p-4 border ${cellBg}`}>
          <div className={`text-xs ${textSecondary} mb-2 flex items-center gap-0.5`}>
            Public API Δ
            <InfoTooltip
              content="Removed explicitly-public methods = breaking changes — all callers must be updated or fail at runtime. Added = new API surface requiring documentation. API surface changes are a top-3 predictor of post-merge defects. Java: exact (from access modifier). TypeScript/Python/Go: heuristic (private if name starts with _ or #)."
              paper="Kim et al. (2008), IEEE TSE"
              isDarkMode={isDarkMode}
            />
          </div>
          <div className="flex items-center gap-2">
            <span className="text-rose-400 font-black text-xl tabular-nums flex items-center gap-0.5">
              <Minus className="w-3.5 h-3.5" />{removedCount}
            </span>
            <span className={`text-xs ${textMuted}`}>/</span>
            <span className="text-emerald-400 font-black text-xl tabular-nums flex items-center gap-0.5">
              <Plus className="w-3.5 h-3.5" />{addedCount}
            </span>
          </div>
          <div className={`text-xs mt-1 ${removedCount > 0 ? 'text-rose-400' : textMuted}`}>
            {removedCount > 0 ? `⚠ ${removedCount} removed — verify callers` : 'no breaking removals'}
          </div>
        </div>
      </div>

      {/* Breaking change warning */}
      {removedCount > 0 && (
        <div className={`flex items-start gap-2 px-4 py-3 rounded-lg border mb-5 ${
          isDarkMode ? 'border-rose-500/20 bg-rose-500/5' : 'border-rose-200 bg-rose-50'
        }`}>
          <AlertCircle className="w-4 h-4 text-rose-400 mt-0.5 flex-shrink-0" />
          <span className={`text-xs leading-relaxed ${isDarkMode ? 'text-rose-300' : 'text-rose-700'}`}>
            <span className="font-bold">{removedCount} public method{removedCount > 1 ? 's' : ''} removed</span>
            {' '}— breaking change risk. All callers must be found and updated before merge.
            Missed callers compile but fail at runtime.{' '}
            <span className={textMuted}>Kim et al. (2008): API surface changes are a top-3 predictor of post-merge defects.</span>
          </span>
        </div>
      )}

      {/* Hotspot methods */}
      {hotspotMethodIds && hotspotMethodIds.length > 0 && (
        <div>
          <div className="flex items-center gap-2 mb-3">
            <Zap className={`w-4 h-4 ${isDarkMode ? 'text-amber-400' : 'text-amber-600'}`} />
            <span className={`text-xs font-semibold uppercase tracking-wider ${textSecondary}`}>
              Hotspot Methods
            </span>
            <InfoTooltip
              content="Methods with highest graph centrality: centrality = (inDegree + outDegree) / max(1, 2×(n−1)). A method with centrality >0.5 is load-bearing — changes here propagate to many callers. Sorted: highest centrality first."
              paper="Zimmermann et al. (2008), PROMISE"
              isDarkMode={isDarkMode}
            />
          </div>
          <div className="space-y-2">
            {hotspotMethodIds.slice(0, 5).map((id, i) => (
              <div key={id} className={`flex items-center gap-3 px-3 py-2.5 rounded-lg border ${cellBg}`}>
                <span className={`
                  text-xs font-bold w-5 h-5 flex items-center justify-center rounded-full flex-shrink-0
                  ${i === 0 ? 'bg-rose-500/15 text-rose-400'
                    : i < 3 ? 'bg-amber-500/15 text-amber-400'
                    : 'bg-slate-500/15 text-slate-400'}
                `}>
                  {i + 1}
                </span>
                <div className="flex-1 min-w-0">
                  <div className={`text-xs font-mono font-semibold ${textPrimary} truncate`}>
                    {shortMethod(id)}
                  </div>
                  <div className={`text-xs ${textMuted} truncate`}>{methodPkg(id)}</div>
                </div>
                {i === 0 && (
                  <span className={`text-xs ${isDarkMode ? 'text-rose-400' : 'text-rose-600'} font-medium whitespace-nowrap flex-shrink-0`}>
                    highest centrality
                  </span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};