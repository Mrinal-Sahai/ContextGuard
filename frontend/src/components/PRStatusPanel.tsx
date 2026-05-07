import React, { useState } from 'react';
import {
  CheckCircle2, XCircle, AlertTriangle, Clock, GitMerge,
  Code2, ChevronDown, ChevronUp, FileX, Shield
} from 'lucide-react';
import { MergeConflictStatus, CompilationStatus } from '../types';

interface Props {
  mergeConflictStatus?: MergeConflictStatus;
  compilationStatus?: CompilationStatus;
  isDarkMode?: boolean;
}

const MERGE_STATE_LABELS: Record<string, string> = {
  clean:     'Ready to merge',
  dirty:     'Merge conflicts',
  unstable:  'Checks failing',
  blocked:   'Blocked by rules',
  behind:    'Branch is behind',
  draft:     'Draft PR',
  unknown:   'Status unknown',
};

function MergeStateChip({ state, isDark }: { state: string; isDark: boolean }) {
  const colors: Record<string, string> = {
    clean:    isDark ? 'bg-emerald-900/50 text-emerald-300 border-emerald-700' : 'bg-emerald-50 text-emerald-700 border-emerald-200',
    dirty:    isDark ? 'bg-rose-900/50 text-rose-300 border-rose-700' : 'bg-rose-50 text-rose-700 border-rose-200',
    unstable: isDark ? 'bg-amber-900/50 text-amber-300 border-amber-700' : 'bg-amber-50 text-amber-700 border-amber-200',
    blocked:  isDark ? 'bg-orange-900/50 text-orange-300 border-orange-700' : 'bg-orange-50 text-orange-700 border-orange-200',
    behind:   isDark ? 'bg-blue-900/50 text-blue-300 border-blue-700' : 'bg-blue-50 text-blue-700 border-blue-200',
    draft:    isDark ? 'bg-slate-700/50 text-slate-300 border-slate-600' : 'bg-slate-100 text-slate-600 border-slate-300',
    unknown:  isDark ? 'bg-slate-700/50 text-slate-300 border-slate-600' : 'bg-slate-100 text-slate-600 border-slate-300',
  };
  const cls = colors[state] ?? colors.unknown;
  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium border ${cls}`}>
      {MERGE_STATE_LABELS[state] ?? state}
    </span>
  );
}

function LangBadge({ lang, isDark }: { lang: string; isDark: boolean }) {
  const LANG_COLORS: Record<string, string> = {
    java:       'bg-orange-100 text-orange-700 border-orange-200',
    typescript: 'bg-blue-100 text-blue-700 border-blue-200',
    python:     'bg-yellow-100 text-yellow-700 border-yellow-200',
    go:         'bg-cyan-100 text-cyan-700 border-cyan-200',
    javascript: 'bg-yellow-100 text-yellow-600 border-yellow-200',
    ruby:       'bg-red-100 text-red-700 border-red-200',
  };
  const LANG_COLORS_DARK: Record<string, string> = {
    java:       'bg-orange-900/40 text-orange-300 border-orange-700',
    typescript: 'bg-blue-900/40 text-blue-300 border-blue-700',
    python:     'bg-yellow-900/40 text-yellow-300 border-yellow-700',
    go:         'bg-cyan-900/40 text-cyan-300 border-cyan-700',
    javascript: 'bg-yellow-900/40 text-yellow-300 border-yellow-700',
    ruby:       'bg-red-900/40 text-red-300 border-red-700',
  };
  const map = isDark ? LANG_COLORS_DARK : LANG_COLORS;
  const cls = map[lang.toLowerCase()] ?? (isDark
    ? 'bg-slate-700 text-slate-300 border-slate-600'
    : 'bg-slate-100 text-slate-600 border-slate-200');
  return (
    <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-xs font-mono border ${cls}`}>
      {lang}
    </span>
  );
}

export const PRStatusPanel: React.FC<Props> = ({
  mergeConflictStatus,
  compilationStatus,
  isDarkMode = true,
}) => {
  const [conflictsExpanded, setConflictsExpanded] = useState(false);
  const [errorsExpanded, setErrorsExpanded] = useState(false);

  const dark = isDarkMode;
  const card = dark ? 'bg-slate-800/60 border-slate-700' : 'bg-white border-slate-200';
  const text = dark ? 'text-slate-100' : 'text-slate-900';
  const sub  = dark ? 'text-slate-400' : 'text-slate-500';

  const hasMerge = !!mergeConflictStatus;
  const hasCompile = !!compilationStatus;

  if (!hasMerge && !hasCompile) return null;

  // ── Merge conflict section ──────────────────────────────────────────────────
  const mergeIcon = () => {
    if (!mergeConflictStatus) return null;
    const { mergeable, hasConflicts, mergeableState } = mergeConflictStatus;
    if (mergeable === null || mergeableState === 'unknown') {
      return <Clock className="w-5 h-5 text-slate-400" />;
    }
    if (hasConflicts) return <XCircle className="w-5 h-5 text-rose-400" />;
    if (mergeableState === 'unstable') return <AlertTriangle className="w-5 h-5 text-amber-400" />;
    if (mergeableState === 'blocked') return <Shield className="w-5 h-5 text-orange-400" />;
    return <CheckCircle2 className="w-5 h-5 text-emerald-400" />;
  };

  const mergeLabel = () => {
    if (!mergeConflictStatus) return '';
    const { mergeable, hasConflicts, conflictFileCount, mergeableState } = mergeConflictStatus;
    if (mergeable === null) return 'Mergeability computing…';
    if (hasConflicts) return `${conflictFileCount > 0 ? `${conflictFileCount} file${conflictFileCount !== 1 ? 's' : ''} with` : 'Merge'} conflicts`;
    if (mergeableState === 'behind') return 'Branch is behind base — update needed';
    if (mergeableState === 'blocked') return 'Merge blocked by branch protection rules';
    if (mergeableState === 'unstable') return 'CI checks are failing';
    return 'No merge conflicts';
  };

  // ── Compilation section ─────────────────────────────────────────────────────
  const compileIcon = () => {
    if (!compilationStatus) return null;
    if (compilationStatus.hasErrors) return <XCircle className="w-5 h-5 text-rose-400" />;
    if (compilationStatus.warningCount > 0) return <AlertTriangle className="w-5 h-5 text-amber-400" />;
    return <CheckCircle2 className="w-5 h-5 text-emerald-400" />;
  };

  const compileLabel = () => {
    if (!compilationStatus) return '';
    const { hasErrors, errorCount, warningCount, parsedLanguages } = compilationStatus;
    const langs = parsedLanguages?.join(', ') ?? '';
    if (!hasErrors && warningCount === 0)
      return `No parse errors${langs ? ` (${langs})` : ''}`;
    const parts = [];
    if (errorCount > 0) parts.push(`${errorCount} error${errorCount !== 1 ? 's' : ''}`);
    if (warningCount > 0) parts.push(`${warningCount} warning${warningCount !== 1 ? 's' : ''}`);
    return parts.join(', ');
  };

  return (
    <div className={`rounded-xl border ${card} p-5 space-y-4`}>
      <div className="flex items-center gap-2">
        <Code2 className={`w-4 h-4 ${dark ? 'text-indigo-400' : 'text-indigo-600'}`} />
        <h3 className={`text-sm font-semibold ${text}`}>PR Health Checks</h3>
      </div>

      <div className="space-y-3">
        {/* ── Merge Conflict Row ── */}
        {hasMerge && (
          <div className={`rounded-lg border ${dark ? 'border-slate-700 bg-slate-900/40' : 'border-slate-100 bg-slate-50'} p-3`}>
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-2.5 min-w-0">
                <div className="mt-0.5 flex-shrink-0">{mergeIcon()}</div>
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`text-sm font-medium ${text}`}>Merge Status</span>
                    <MergeStateChip state={mergeConflictStatus!.mergeableState} isDark={dark} />
                  </div>
                  <p className={`text-xs mt-0.5 ${sub}`}>{mergeLabel()}</p>
                </div>
              </div>
              {mergeConflictStatus!.hasConflicts && mergeConflictStatus!.conflictingFiles.length > 0 && (
                <button
                  onClick={() => setConflictsExpanded(v => !v)}
                  className={`flex items-center gap-1 text-xs ${dark ? 'text-slate-400 hover:text-slate-200' : 'text-slate-500 hover:text-slate-700'} flex-shrink-0`}
                >
                  {conflictsExpanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                  {mergeConflictStatus!.conflictFileCount} file{mergeConflictStatus!.conflictFileCount !== 1 ? 's' : ''}
                </button>
              )}
            </div>

            {conflictsExpanded && mergeConflictStatus!.conflictingFiles.length > 0 && (
              <div className={`mt-3 pt-3 border-t ${dark ? 'border-slate-700' : 'border-slate-200'} space-y-1`}>
                <p className={`text-xs font-medium ${sub} mb-2`}>Potentially conflicting files:</p>
                {mergeConflictStatus!.conflictingFiles.map((f, i) => (
                  <div key={i} className="flex items-center gap-2">
                    <FileX className={`w-3 h-3 flex-shrink-0 ${dark ? 'text-rose-400' : 'text-rose-500'}`} />
                    <span className={`text-xs font-mono truncate ${dark ? 'text-slate-300' : 'text-slate-600'}`}>{f}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* ── Compilation Row ── */}
        {hasCompile && (
          <div className={`rounded-lg border ${dark ? 'border-slate-700 bg-slate-900/40' : 'border-slate-100 bg-slate-50'} p-3`}>
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-2.5 min-w-0">
                <div className="mt-0.5 flex-shrink-0">{compileIcon()}</div>
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`text-sm font-medium ${text}`}>Parse / Compile</span>
                    {compilationStatus!.parsedLanguages?.map(l => (
                      <LangBadge key={l} lang={l} isDark={dark} />
                    ))}
                  </div>
                  <p className={`text-xs mt-0.5 ${sub}`}>{compileLabel()}</p>
                </div>
              </div>
              {compilationStatus!.errors.length > 0 && (
                <button
                  onClick={() => setErrorsExpanded(v => !v)}
                  className={`flex items-center gap-1 text-xs ${dark ? 'text-slate-400 hover:text-slate-200' : 'text-slate-500 hover:text-slate-700'} flex-shrink-0`}
                >
                  {errorsExpanded ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                  details
                </button>
              )}
            </div>

            {errorsExpanded && compilationStatus!.errors.length > 0 && (
              <div className={`mt-3 pt-3 border-t ${dark ? 'border-slate-700' : 'border-slate-200'} space-y-2`}>
                {compilationStatus!.errors.map((err, i) => (
                  <div key={i} className={`rounded p-2 ${dark ? 'bg-slate-800' : 'bg-white border border-slate-200'}`}>
                    <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                      <LangBadge lang={err.language} isDark={dark} />
                      <span className={`text-xs font-mono truncate ${dark ? 'text-slate-300' : 'text-slate-600'}`}>
                        {err.file}{err.line > 0 ? `:${err.line}` : ''}
                      </span>
                      <span className={`ml-auto text-xs font-semibold ${
                        err.severity === 'ERROR'
                          ? (dark ? 'text-rose-400' : 'text-rose-600')
                          : (dark ? 'text-amber-400' : 'text-amber-600')
                      }`}>{err.severity}</span>
                    </div>
                    <p className={`text-xs ${dark ? 'text-slate-400' : 'text-slate-500'}`}>{err.message}</p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default PRStatusPanel;
