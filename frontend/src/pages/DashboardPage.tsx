import React, { useState, useEffect, useCallback } from 'react';
import {
  Shield, Github, LogOut, Sun, Moon, RefreshCw, ExternalLink,
  Loader2, AlertTriangle, GitPullRequest, Clock, Target, Zap,
  History, Search, CheckCircle2, XCircle
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useAuth, authHeaders, API_BASE } from '../contexts/AuthContext';

/* ─── types ──────────────────────────────────────────────────────────────── */

interface ReviewRequest {
  number: number;
  title: string;
  prUrl: string;
  author: string;
  authorAvatar: string;
  owner: string;
  repo: string;
  updatedAt: string;
  draft: boolean;
}

interface HistoryItem {
  analysisId: string;
  owner: string;
  repo: string;
  prNumber: number;
  title: string;
  prUrl: string;
  riskLevel: string;
  difficulty: string;
  analyzedBy: string;
  analyzedAt: string;
}

/* ─── helpers ────────────────────────────────────────────────────────────── */

const PR_URL_REGEX = /^https:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+$/;

const validatePrUrl = (url: string): string | null => {
  if (!url.trim()) return null; // empty — no error shown yet
  if (!url.startsWith('https://github.com/'))
    return 'Must be a github.com URL';
  if (!PR_URL_REGEX.test(url.trim()))
    return 'Format: https://github.com/owner/repo/pull/123';
  return null; // valid
};

const timeAgo = (iso: string) => {
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 60)  return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24)   return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
};

const riskColor: Record<string, string> = {
  LOW:      'text-emerald-400 bg-emerald-500/10 border-emerald-500/30',
  MEDIUM:   'text-amber-400  bg-amber-500/10  border-amber-500/30',
  HIGH:     'text-orange-400 bg-orange-500/10 border-orange-500/30',
  CRITICAL: 'text-rose-400   bg-rose-500/10   border-rose-500/30',
};

/* ─── component ──────────────────────────────────────────────────────────── */

const DashboardPage: React.FC = () => {
  const { user, token, logout } = useAuth();
  const navigate = useNavigate();
  const [isDark, setDark] = useState(true);

  // Review requests
  const [requests, setRequests]     = useState<ReviewRequest[]>([]);
  const [reqLoading, setReqLoading] = useState(false);
  const [reqError, setReqError]     = useState<string | null>(null);

  // URL panel
  const [prUrl, setPrUrl]           = useState('');
  const [urlValidationError, setUrlValidationError] = useState<string | null>(null);
  const [aiProvider, setAiProvider] = useState<'OPENAI' | 'GEMINI'>('OPENAI');
  const [responseLength, setResponseLength] = useState<'SHORT' | 'MEDIUM' | 'LONG'>('MEDIUM');
  const [diagramBudget, setDiagramBudget]   = useState<'COMPACT' | 'STANDARD' | 'DETAILED'>('STANDARD');
  const [analyzing, setAnalyzing]   = useState<string | null>(null); // stores prUrl being analyzed
  const [urlError, setUrlError]     = useState<string | null>(null);

  const budgetMap = {
    COMPACT:  { diagramMaxParticipants: 6,  diagramMaxArrows: 15 },
    STANDARD: { diagramMaxParticipants: 10, diagramMaxArrows: 25 },
    DETAILED: { diagramMaxParticipants: 15, diagramMaxArrows: 35 },
  };
  const tokenMap = { SHORT: 4000, MEDIUM: 8000, LONG: 12000 };

  // History
  const [history, setHistory]       = useState<HistoryItem[]>([]);
  const [histLoading, setHistLoad]  = useState(false);

  /* fetch review requests */
  const loadReviewRequests = useCallback(async () => {
    setReqLoading(true);
    setReqError(null);
    try {
      const r = await fetch(`${API_BASE}/github/review-requests`, {
        headers: authHeaders(token),
      });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      setRequests(await r.json());
    } catch (e: any) {
      setReqError(e.message || 'Failed to load review requests');
    } finally {
      setReqLoading(false);
    }
  }, [token]);

  /* fetch history */
  const loadHistory = useCallback(async () => {
    setHistLoad(true);
    try {
      const r = await fetch(`${API_BASE}/pr-analysis/history?limit=15`, {
        headers: authHeaders(token),
      });
      if (r.ok) setHistory(await r.json());
    } finally {
      setHistLoad(false);
    }
  }, [token]);

  useEffect(() => {
    loadReviewRequests();
    loadHistory();
  }, [loadReviewRequests, loadHistory]);

  /* analyze any PR URL (used by both URL panel and one-click) */
  const analyzePR = async (url: string) => {
    if (!url || analyzing) return;
    setAnalyzing(url);
    setUrlError(null);
    try {
      const r = await fetch(`${API_BASE}/pr-analysis/analyze`, {
        method: 'POST',
        headers: authHeaders(token),
        body: JSON.stringify({
          prUrl: url,
          aiProvider,
          ...budgetMap[diagramBudget],
        }),
      });
      const json = await r.json();
      if (!json.success) throw new Error(json.error || json.message || 'Analysis failed');
      navigate(`/review/${json.data.analysisId}`);
    } catch (e: any) {
      setUrlError(e.message || 'Analysis failed');
    } finally {
      setAnalyzing(null);
    }
  };

  /* ── theme ──────────────────────────────────────────────────────────────── */
  const bg     = isDark ? 'bg-slate-950 text-white'                : 'bg-slate-50 text-slate-900';
  const card   = isDark ? 'bg-slate-900/60 border-slate-700/50'   : 'bg-white border-slate-200';
  const muted  = isDark ? 'text-slate-400'                         : 'text-slate-600';
  const input  = isDark ? 'bg-slate-800 border-slate-700 text-white placeholder-slate-500'
                        : 'bg-white border-slate-300 text-slate-900 placeholder-slate-400';
  const border = isDark ? 'border-slate-700/50' : 'border-slate-200';

  /* ── render ─────────────────────────────────────────────────────────────── */
  return (
    <div className={`min-h-screen ${bg} transition-colors duration-300`}>

      {/* Header */}
      <header className={`border-b ${border} backdrop-blur-sm ${isDark ? 'bg-slate-900/40' : 'bg-white/80'}`}>
        <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-gradient-to-br from-indigo-600 to-purple-600 p-2 rounded-xl">
              <Shield className="w-6 h-6 text-white" />
            </div>
            <span className="text-xl font-black bg-gradient-to-r from-indigo-400 to-purple-400 bg-clip-text text-transparent">
              ContextGuard
            </span>
          </div>

          <div className="flex items-center gap-3">
            <button onClick={() => setDark(!isDark)}
              className={`p-2 rounded-lg border ${isDark ? 'border-slate-700 hover:bg-slate-800' : 'border-slate-200 hover:bg-slate-100'} transition-colors`}>
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </button>

            {user && (
              <div className="flex items-center gap-2">
                <img src={user.avatarUrl} alt={user.login}
                  className="w-8 h-8 rounded-full border-2 border-indigo-500/50" />
                <span className={`text-sm font-medium ${muted}`}>{user.login}</span>
              </div>
            )}

            <button onClick={logout}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm ${isDark ? 'border-slate-700 hover:bg-slate-800' : 'border-slate-200 hover:bg-slate-100'} transition-colors`}>
              <LogOut className="w-4 h-4" />
              Sign out
            </button>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-6 py-8 space-y-8">

        {/* ── Global Analysis Settings ─────────────────────────────────────────── */}
        <section className={`border rounded-2xl ${card} px-6 py-4`}>
          <div className="flex flex-wrap items-center gap-6">
            <span className={`text-xs font-bold uppercase tracking-wider ${muted} shrink-0`}>Analysis Settings</span>

            {/* AI Provider */}
            <div className="flex items-center gap-2">
              <span className={`text-xs font-semibold ${muted}`}>AI</span>
              <div className="flex gap-1">
                {(['OPENAI', 'GEMINI'] as const).map(p => (
                  <button key={p} onClick={() => setAiProvider(p)}
                    className={`px-2.5 py-1 rounded-md border text-xs font-semibold transition-all ${
                      aiProvider === p
                        ? 'border-indigo-500 bg-indigo-500/10 text-indigo-400'
                        : isDark ? 'border-slate-700 text-slate-400 hover:border-slate-600' : 'border-slate-200 text-slate-600 hover:border-slate-300'
                    }`}>
                    {p === 'OPENAI' ? 'OpenAI' : 'Gemini'}
                  </button>
                ))}
              </div>
            </div>

            <div className={`w-px h-5 ${isDark ? 'bg-slate-700' : 'bg-slate-200'} shrink-0`} />

            {/* Response Length */}
            <div className="flex items-center gap-2">
              <span className={`text-xs font-semibold ${muted}`}>Narrative</span>
              <div className="flex gap-1">
                {(['SHORT', 'MEDIUM', 'LONG'] as const).map(l => (
                  <button key={l} onClick={() => setResponseLength(l)}
                    title={l === 'SHORT' ? '~4k tokens' : l === 'MEDIUM' ? '~8k tokens' : '~12k tokens'}
                    className={`px-2.5 py-1 rounded-md border text-xs font-semibold transition-all ${
                      responseLength === l
                        ? 'border-purple-500 bg-purple-500/10 text-purple-400'
                        : isDark ? 'border-slate-700 text-slate-400 hover:border-slate-600' : 'border-slate-200 text-slate-600 hover:border-slate-300'
                    }`}>
                    {l === 'SHORT' ? 'Short' : l === 'MEDIUM' ? 'Medium' : 'Long'}
                  </button>
                ))}
              </div>
            </div>

            <div className={`w-px h-5 ${isDark ? 'bg-slate-700' : 'bg-slate-200'} shrink-0`} />

            {/* Diagram Budget */}
            <div className="flex items-center gap-2">
              <span className={`text-xs font-semibold ${muted}`}>Diagram</span>
              <div className="flex gap-1">
                {(['COMPACT', 'STANDARD', 'DETAILED'] as const).map(b => (
                  <button key={b} onClick={() => setDiagramBudget(b)}
                    title={b === 'COMPACT' ? '6 participants · 15 arrows' : b === 'STANDARD' ? '10 participants · 25 arrows' : '15 participants · 35 arrows'}
                    className={`px-2.5 py-1 rounded-md border text-xs font-semibold transition-all ${
                      diagramBudget === b
                        ? 'border-cyan-500 bg-cyan-500/10 text-cyan-400'
                        : isDark ? 'border-slate-700 text-slate-400 hover:border-slate-600' : 'border-slate-200 text-slate-600 hover:border-slate-300'
                    }`}>
                    {b === 'COMPACT' ? 'Compact' : b === 'STANDARD' ? 'Standard' : 'Detailed'}
                  </button>
                ))}
              </div>
            </div>

            <span className={`ml-auto text-xs ${muted} hidden lg:block`}>
              Applied to all analyses on this page
            </span>
          </div>
        </section>

        {/* Top row: Review Requests + Quick Analyze */}
        <div className="grid lg:grid-cols-5 gap-6">

          {/* ── Review Requests panel (3/5) ───────────────────────────────── */}
          <section className={`lg:col-span-3 border rounded-2xl ${card} flex flex-col`}>
            <div className="px-6 py-4 border-b border-inherit flex items-center justify-between">
              <div className="flex items-center gap-2">
                <GitPullRequest className="w-5 h-5 text-indigo-400" />
                <h2 className="font-semibold">Review Requests</h2>
                {requests.length > 0 && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-indigo-500/20 text-indigo-400 border border-indigo-500/30">
                    {requests.length}
                  </span>
                )}
              </div>
              <button onClick={loadReviewRequests} disabled={reqLoading}
                className={`p-1.5 rounded-lg ${isDark ? 'hover:bg-slate-800' : 'hover:bg-slate-100'} transition-colors`}>
                <RefreshCw className={`w-4 h-4 ${muted} ${reqLoading ? 'animate-spin' : ''}`} />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto max-h-[520px]">
              {reqLoading && requests.length === 0 ? (
                <div className="flex items-center justify-center h-40 gap-2">
                  <Loader2 className="w-5 h-5 animate-spin text-indigo-400" />
                  <span className={muted}>Loading PRs…</span>
                </div>
              ) : reqError ? (
                <div className="p-6 flex items-start gap-3">
                  <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
                  <div>
                    <p className="text-sm font-semibold text-rose-400">Could not load review requests</p>
                    <p className={`text-xs mt-1 ${muted}`}>{reqError}</p>
                  </div>
                </div>
              ) : requests.length === 0 ? (
                <div className="flex flex-col items-center justify-center h-40 gap-2">
                  <CheckCircle2 className="w-8 h-8 text-emerald-400" />
                  <p className={`text-sm ${muted}`}>No pending review requests — you're all caught up!</p>
                </div>
              ) : (
                <ul className="divide-y divide-slate-700/30">
                  {requests.map(pr => {
                    const isAnalyzing = analyzing === pr.prUrl;
                    return (
                      <li key={`${pr.owner}/${pr.repo}#${pr.number}`}
                        className={`px-6 py-4 flex items-start gap-4 ${isDark ? 'hover:bg-slate-800/40' : 'hover:bg-slate-50'} transition-colors`}>
                        <img src={pr.authorAvatar} alt={pr.author}
                          className="w-8 h-8 rounded-full shrink-0 mt-0.5" />
                        <div className="flex-1 min-w-0">
                          <div className="flex items-start justify-between gap-2">
                            <a href={pr.prUrl} target="_blank" rel="noreferrer"
                              className="text-sm font-medium hover:text-indigo-400 transition-colors line-clamp-2 leading-tight">
                              {pr.title}
                            </a>
                            {pr.draft && (
                              <span className="shrink-0 text-xs px-1.5 py-0.5 rounded bg-slate-700/50 text-slate-400 border border-slate-600/50">
                                Draft
                              </span>
                            )}
                          </div>
                          <div className={`flex items-center gap-3 mt-1 text-xs ${muted}`}>
                            <span className="font-mono">{pr.owner}/{pr.repo} #{pr.number}</span>
                            <span>·</span>
                            <span className="flex items-center gap-1">
                              <Github className="w-3 h-3" />{pr.author}
                            </span>
                            <span>·</span>
                            <span className="flex items-center gap-1">
                              <Clock className="w-3 h-3" />{timeAgo(pr.updatedAt)}
                            </span>
                          </div>
                        </div>
                        <div className="shrink-0 flex items-center gap-1.5">
                          <a
                            href={pr.prUrl}
                            target="_blank"
                            rel="noreferrer"
                            onClick={e => e.stopPropagation()}
                            className={`flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-xs font-semibold border transition-colors ${isDark ? 'border-slate-600 text-slate-300 hover:bg-slate-700' : 'border-slate-300 text-slate-600 hover:bg-slate-100'}`}>
                            <ExternalLink className="w-3 h-3" />PR
                          </a>
                          <button
                            onClick={() => analyzePR(pr.prUrl)}
                            disabled={!!analyzing}
                            className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 disabled:cursor-not-allowed rounded-lg text-xs font-semibold text-white transition-colors">
                            {isAnalyzing ? (
                              <><Loader2 className="w-3.5 h-3.5 animate-spin" />Analyzing…</>
                            ) : (
                              <><Zap className="w-3.5 h-3.5" />Analyze</>
                            )}
                          </button>
                        </div>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </section>

          {/* ── Quick Analyze panel (2/5) ─────────────────────────────────── */}
          <section className={`lg:col-span-2 border rounded-2xl ${card} flex flex-col`}>
            <div className={`px-6 py-4 border-b border-inherit flex items-center gap-2`}>
              <Search className="w-5 h-5 text-purple-400" />
              <h2 className="font-semibold">Analyze by URL</h2>
            </div>
            <div className="p-6 flex flex-col gap-5 flex-1">
              <div>
                <label className={`block text-xs font-semibold ${muted} mb-1.5`}>GitHub PR URL</label>
                <div className="relative">
                  <Github className={`absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 ${muted}`} />
                  <input
                    type="text"
                    value={prUrl}
                    onChange={e => {
                      const v = e.target.value;
                      setPrUrl(v);
                      setUrlError(null);
                      setUrlValidationError(validatePrUrl(v));
                    }}
                    onKeyDown={e => e.key === 'Enter' && !urlValidationError && analyzePR(prUrl)}
                    placeholder="https://github.com/owner/repo/pull/123"
                    className={`w-full pl-9 pr-9 py-2.5 text-sm rounded-lg border transition-colors focus:outline-none focus:ring-2 focus:border-transparent ${
                      urlValidationError
                        ? `border-rose-500/70 focus:ring-rose-500 ${isDark ? 'bg-slate-800 text-white placeholder-slate-500' : 'bg-white text-slate-900 placeholder-slate-400'}`
                        : prUrl && !urlValidationError
                          ? `border-emerald-500/70 focus:ring-emerald-500 ${isDark ? 'bg-slate-800 text-white placeholder-slate-500' : 'bg-white text-slate-900 placeholder-slate-400'}`
                          : `${input} focus:ring-indigo-500`
                    }`}
                  />
                  {/* Inline validation icon */}
                  {prUrl && (
                    <div className="absolute right-3 top-1/2 -translate-y-1/2">
                      {urlValidationError
                        ? <XCircle className="w-4 h-4 text-rose-400" />
                        : <CheckCircle2 className="w-4 h-4 text-emerald-400" />
                      }
                    </div>
                  )}
                </div>
                {/* Validation message — format error or API submit error */}
                {(urlValidationError || urlError) && (
                  <p className="mt-1.5 text-xs text-rose-400 flex items-center gap-1">
                    <XCircle className="w-3.5 h-3.5" />
                    {urlValidationError ?? urlError}
                  </p>
                )}
                {prUrl && !urlValidationError && (
                  <p className="mt-1.5 text-xs text-emerald-400 flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" />Valid GitHub PR URL
                  </p>
                )}
              </div>

              <p className={`text-xs ${muted} flex items-center gap-1.5`}>
                <span className="px-1.5 py-0.5 rounded border text-indigo-400 border-indigo-500/30 bg-indigo-500/10 font-semibold">{aiProvider}</span>
                <span className="px-1.5 py-0.5 rounded border text-purple-400 border-purple-500/30 bg-purple-500/10 font-semibold">{responseLength}</span>
                <span className="px-1.5 py-0.5 rounded border text-cyan-400 border-cyan-500/30 bg-cyan-500/10 font-semibold">{diagramBudget}</span>
                <span className={muted}>— adjust in settings bar above</span>
              </p>

              <button
                onClick={() => analyzePR(prUrl)}
                disabled={!prUrl || !!urlValidationError || !!analyzing}
                className="w-full py-3 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 disabled:from-slate-700 disabled:to-slate-700 disabled:cursor-not-allowed rounded-xl font-bold text-white text-sm transition-all flex items-center justify-center gap-2 shadow-lg hover:shadow-indigo-500/30">
                {analyzing === prUrl ? (
                  <><Loader2 className="w-4 h-4 animate-spin" />Analyzing…</>
                ) : (
                  <><Target className="w-4 h-4" />Analyze Pull Request</>
                )}
              </button>

              <p className={`text-xs ${muted} text-center`}>
                Works for any public repo. Private repos use your GitHub OAuth token automatically.
              </p>
            </div>
          </section>
        </div>

        {/* History panel */}
        <section className={`border rounded-2xl ${card}`}>
          <div className={`px-6 py-4 border-b border-inherit flex items-center justify-between`}>
            <div className="flex items-center gap-2">
              <History className="w-5 h-5 text-cyan-400" />
              <h2 className="font-semibold">Recent Analyses</h2>
            </div>
            <button onClick={loadHistory} disabled={histLoading}>
              <RefreshCw className={`w-4 h-4 ${muted} ${histLoading ? 'animate-spin' : ''}`} />
            </button>
          </div>

          {histLoading && history.length === 0 ? (
            <div className="flex items-center justify-center h-28 gap-2">
              <Loader2 className="w-5 h-5 animate-spin text-cyan-400" />
              <span className={muted}>Loading history…</span>
            </div>
          ) : history.length === 0 ? (
            <div className="flex items-center justify-center h-28">
              <span className={`text-sm ${muted}`}>No analyses yet — run your first one above.</span>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className={`text-xs uppercase tracking-wider ${muted} border-b ${border}`}>
                    <th className="px-6 py-3 text-left font-semibold">PR</th>
                    <th className="px-4 py-3 text-left font-semibold">Risk</th>
                    <th className="px-4 py-3 text-left font-semibold">Difficulty</th>
                    <th className="px-4 py-3 text-left font-semibold">Analyzed by</th>
                    <th className="px-4 py-3 text-left font-semibold">When</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-700/20">
                  {history.map(h => (
                    <tr key={h.analysisId}
                      className={`${isDark ? 'hover:bg-slate-800/40' : 'hover:bg-slate-50'} transition-colors cursor-pointer`}
                      onClick={() => navigate(`/review/${h.analysisId}`)}>
                      <td className="px-6 py-3">
                        <div className="font-medium line-clamp-1 max-w-xs">{h.title || `${h.owner}/${h.repo} #${h.prNumber}`}</div>
                        <div className={`text-xs ${muted} font-mono`}>{h.owner}/{h.repo} #{h.prNumber}</div>
                      </td>
                      <td className="px-4 py-3">
                        <span className={`text-xs px-2 py-0.5 rounded-full border font-semibold ${riskColor[h.riskLevel] || 'text-slate-400'}`}>
                          {h.riskLevel || '—'}
                        </span>
                      </td>
                      <td className={`px-4 py-3 text-xs ${muted}`}>{h.difficulty || '—'}</td>
                      <td className={`px-4 py-3 text-xs font-mono ${muted}`}>
                        {h.analyzedBy ? `@${h.analyzedBy}` : '—'}
                      </td>
                      <td className={`px-4 py-3 text-xs ${muted}`}>{timeAgo(h.analyzedAt)}</td>
                      <td className="px-4 py-3">
                        <ExternalLink className={`w-4 h-4 ${muted}`} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </main>
    </div>
  );
};

export default DashboardPage;
