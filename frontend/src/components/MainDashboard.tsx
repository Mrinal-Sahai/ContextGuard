import React, { useState } from 'react';
import { Shield, Github, Zap, AlertTriangle, FileCode, GitBranch, Clock, TrendingUp, Target, Brain, CheckCircle2, Info, Loader2, ChevronDown, ChevronRight, ExternalLink, BarChart3, Network, Sun, Moon } from 'lucide-react';
import MermaidDiagram from './MermaidDiagram';
import { PRAnalysisResponse,PRIntelligenceResponse} from '../types/index'
import { formatDate} from '../services/utility';
import { MetricCard } from './MetricCard';
import { InfoTooltip } from './InfoTooltip';
import { BreakdownChart} from './BreakdownChart';
import { RiskLevelBadge } from './RiskLevelBadge';
import { DifficultyBadge } from './DifficultyBadge';
import { NarrativeSection} from './NarrativeSection';
import { FileChangeItem } from './FileChangeItem';
import { useNavigate } from "react-router-dom";



const ContextGuardDashboard: React.FC = () => {
  const [isDarkMode, setIsDarkMode] = useState(false);
  const [prUrl, setPrUrl] = useState('');
  const [aiProvider, setAiProvider] = useState<'GEMINI' | 'OPENAI'>('OPENAI');
  const [githubToken, setGithubToken] = useState('');
  const [aiToken, setAiToken] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const navigate = useNavigate();
  
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysisId, setAnalysisId] = useState<string | null>(null);
  const [analysisData, setAnalysisData] = useState<PRIntelligenceResponse | null>(null);
  const API_BASE_URL= import.meta.env.VITE_API_URL ??  "http://localhost:8080/api/v1"

  const validatePrUrl = (url: string): boolean => {
    const pattern = /^https:\/\/github\.com\/[^/]+\/[^/]+\/pull\/\d+$/;
    return pattern.test(url);
  };

  const handleAnalyze = async () => {
    if (!validatePrUrl(prUrl)) {
      setError('Invalid GitHub PR URL format. Expected: https://github.com/owner/repo/pull/123');
      return;
    }

    setError(null);
    setLoading(true);
    setAnalysisData(null);

    try {
      const analyzeResponse = await fetch(`${API_BASE_URL}/pr-analysis/analyze`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prUrl,
          aiProvider,
          ...(githubToken && { githubToken }),
          ...(aiToken && { aiToken }),
        }),
      });

      if (!analyzeResponse.ok) {
        throw new Error(`Analysis failed: ${analyzeResponse.statusText}`);
      }

      const analyzeResult: PRAnalysisResponse = await analyzeResponse.json();
      
      if (!analyzeResult.success) {
        throw new Error(analyzeResult.error || 'Analysis failed');
      }

      const id = analyzeResult.data.analysisId;
      navigate(`/review/${id}`);

    } catch (err: any) {
      setError(err.message || 'An error occurred during analysis');
    } finally {
      setLoading(false);
    }
  };


  // Theme-based classes
  const bgPrimary = isDarkMode ? 'bg-slate-950' : 'bg-slate-50';
  const bgSecondary = isDarkMode ? 'bg-slate-900/30' : 'bg-white';
  const bgTertiary = isDarkMode ? 'bg-slate-800/30' : 'bg-slate-100';
  const textPrimary = isDarkMode ? 'text-white' : 'text-slate-900';
  const textSecondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const borderColor = isDarkMode ? 'border-slate-800/50' : 'border-slate-200';
  const inputBg = isDarkMode ? 'bg-slate-900/50 border-slate-700' : 'bg-white border-slate-300';
  const buttonBg = isDarkMode ? 'bg-slate-800 hover:bg-slate-700 border-slate-700' : 'bg-white hover:bg-slate-100 border-slate-300';

  return (
    <div className={`min-h-screen ${bgPrimary} ${textPrimary} relative overflow-hidden transition-colors duration-300`}>
      {/* Animated Background */}
      {isDarkMode && (
        <>
          <div className="fixed inset-0 overflow-hidden pointer-events-none">
            <div className="absolute top-0 left-1/4 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl animate-pulse" />
            <div className="absolute bottom-0 right-1/4 w-96 h-96 bg-purple-500/10 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }} />
            <div className="absolute top-1/2 left-1/2 w-96 h-96 bg-cyan-500/5 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '2s' }} />
          </div>
          <div className="fixed inset-0 pointer-events-none opacity-20" style={{
            backgroundImage: 'linear-gradient(rgba(99, 102, 241, 0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(99, 102, 241, 0.1) 1px, transparent 1px)',
            backgroundSize: '50px 50px'
          }} />
        </>
      )}

      <div className="relative z-10">
        {/* Header */}
        <header className={`border-b ${borderColor} backdrop-blur-sm ${bgSecondary}`}>
          <div className="max-w-7xl mx-auto px-6 py-6">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="relative">
                  <div className="absolute inset-0 bg-gradient-to-r from-indigo-500 to-purple-500 rounded-xl blur-lg opacity-50" />
                  <div className="relative bg-gradient-to-br from-indigo-600 to-purple-600 p-3 rounded-xl">
                    <Shield className="w-8 h-8" />
                  </div>
                </div>
                <div>
                  <h1 className="text-3xl font-black bg-gradient-to-r from-indigo-400 via-purple-400 to-pink-400 bg-clip-text text-transparent">
                    ContextGuard
                  </h1>
                  <p className={`text-sm ${textSecondary} font-medium`}>Intelligent PR Review Platform</p>
                </div>
              </div>
              
              <div className="flex items-center gap-3">
                {/* Theme Toggle */}
                <button
                  onClick={() => setIsDarkMode(!isDarkMode)}
                  className={`px-4 py-2 ${buttonBg} rounded-lg border transition-all flex items-center gap-2`}
                >
                  {isDarkMode ? (
                    <>
                      <Sun className="w-4 h-4" />
                      <span className="text-sm font-medium">Light</span>
                    </>
                  ) : (
                    <>
                      <Moon className="w-4 h-4" />
                      <span className="text-sm font-medium">Dark</span>
                    </>
                  )}
                </button>
              </div>
            </div>
          </div>
        </header>

        <main className="max-w-7xl mx-auto px-6 py-12">
          {!analysisData ? (
            // Landing Page / Input Form
            <div className="max-w-3xl mx-auto">
              <div className="text-center mb-12">
                <h2 className={`text-5xl font-black mb-4 ${isDarkMode ? 'bg-gradient-to-r from-white via-slate-200 to-slate-400' : 'bg-gradient-to-r from-slate-900 via-slate-700 to-slate-500'} bg-clip-text text-transparent`}>
                  Analyze Your Pull Request
                </h2>
                <p className={`text-lg ${textSecondary} max-w-2xl mx-auto`}>
                  Get comprehensive risk assessment, difficulty analysis, and AI-powered insights for your GitHub pull requests in seconds.
                </p>
              </div>

              <div className={`${isDarkMode ? 'bg-slate-800/30 border-slate-700/50' : 'bg-white border-slate-200'} backdrop-blur-xl border rounded-2xl p-8 shadow-2xl`}>
                <div className="mb-6">
                  <label className={`block text-sm font-semibold ${textSecondary} mb-2`}>
                    GitHub Pull Request URL
                  </label>
                  <div className="relative">
                    <Github className={`absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 ${textSecondary}`} />
                    <input
                      type="text"
                      value={prUrl}
                      onChange={(e) => setPrUrl(e.target.value)}
                      placeholder="https://github.com/owner/repo/pull/123"
                      className={`w-full pl-12 pr-4 py-4 ${inputBg} rounded-xl ${textPrimary} ${isDarkMode ? 'placeholder-slate-500' : 'placeholder-slate-400'} focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all`}
                    />
                  </div>
                  {error && (
                    <p className="mt-2 text-sm text-rose-400 flex items-center gap-2">
                      <AlertTriangle className="w-4 h-4" />
                      {error}
                    </p>
                  )}
                </div>

                <div className="mb-6">
                  <label className={`block text-sm font-semibold ${textSecondary} mb-3`}>
                    AI Provider
                  </label>
                  <div className="grid grid-cols-2 gap-3">
                    <button
                      onClick={() => setAiProvider('OPENAI')}
                      className={`p-4 rounded-xl border-2 transition-all ${
                        aiProvider === 'OPENAI'
                          ? 'border-indigo-500 bg-indigo-500/10'
                          : isDarkMode 
                            ? 'border-slate-700 bg-slate-900/30 hover:border-slate-600' 
                            : 'border-slate-300 bg-slate-50 hover:border-slate-400'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <Zap className={`w-5 h-5 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />
                        <span className="font-semibold">OpenAI</span>
                      </div>
                    </button>
                    <button
                      onClick={() => setAiProvider('GEMINI')}
                      className={`p-4 rounded-xl border-2 transition-all ${
                        aiProvider === 'GEMINI'
                          ? 'border-indigo-500 bg-indigo-500/10'
                          : isDarkMode 
                            ? 'border-slate-700 bg-slate-900/30 hover:border-slate-600' 
                            : 'border-slate-300 bg-slate-50 hover:border-slate-400'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <Zap className={`w-5 h-5 ${isDarkMode ? 'text-purple-400' : 'text-purple-600'}`} />
                        <span className="font-semibold">Gemini</span>
                      </div>
                    </button>
                  </div>
                </div>

                <div className="mb-6">
                  <button
                    onClick={() => setShowAdvanced(!showAdvanced)}
                    className={`text-sm ${textSecondary} ${isDarkMode ? 'hover:text-indigo-400' : 'hover:text-indigo-600'} transition-colors flex items-center gap-2`}
                  >
                    {showAdvanced ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                    Advanced Options (Optional)
                  </button>
                  
                  {showAdvanced && (
                    <div className={`mt-4 space-y-4 pl-6 border-l-2 ${isDarkMode ? 'border-slate-700' : 'border-slate-300'}`}>
                      <div>
                        <label className={`block text-sm font-medium ${textSecondary} mb-2`}>
                          GitHub Personal Access Token
                        </label>
                        <input
                          type="password"
                          value={githubToken}
                          onChange={(e) => setGithubToken(e.target.value)}
                          placeholder="ghp_xxxxxxxxxxxxx"
                          className={`w-full px-4 py-3 ${inputBg} rounded-lg ${textPrimary} ${isDarkMode ? 'placeholder-slate-600' : 'placeholder-slate-400'} focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent`}
                        />
                      </div>
                      <div>
                        <label className={`block text-sm font-medium ${textSecondary} mb-2`}>
                          AI Provider API Token
                        </label>
                        <input
                          type="password"
                          value={aiToken}
                          onChange={(e) => setAiToken(e.target.value)}
                          placeholder="sk-xxxxxxxxxxxxx"
                          className={`w-full px-4 py-3 ${inputBg} rounded-lg ${textPrimary} ${isDarkMode ? 'placeholder-slate-600' : 'placeholder-slate-400'} focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent`}
                        />
                      </div>
                    </div>
                  )}
                </div>

                <button
                  onClick={handleAnalyze}
                  disabled={loading || !prUrl}
                  className="w-full py-4 px-6 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 disabled:from-slate-700 disabled:to-slate-700 disabled:cursor-not-allowed rounded-xl font-bold text-white transition-all duration-300 shadow-lg hover:shadow-indigo-500/50 disabled:shadow-none flex items-center justify-center gap-3"
                >
                  {loading ? (
                    <>
                      <Loader2 className="w-5 h-5 animate-spin" />
                      Analyzing Pull Request...
                    </>
                  ) : (
                    <>
                      <Target className="w-5 h-5" />
                      Analyze Pull Request
                    </>
                  )}
                </button>
              </div>

              {/* Feature Highlights */}
              <div className="grid md:grid-cols-3 gap-6 mt-12">
                {[
                  {
                    icon: <AlertTriangle className={`w-6 h-6 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />,
                    title: 'Risk Assessment',
                    description: 'Multi-dimensional risk scoring with critical file detection and blast radius analysis.',
                    color: isDarkMode ? 'bg-indigo-500/20 hover:border-indigo-500/50' : 'bg-indigo-100 hover:border-indigo-400',
                  },
                  {
                    icon: <Brain className={`w-6 h-6 ${isDarkMode ? 'text-purple-400' : 'text-purple-600'}`} />,
                    title: 'AI Insights',
                    description: 'Comprehensive narrative analysis generated by advanced language models.',
                    color: isDarkMode ? 'bg-purple-500/20 hover:border-purple-500/50' : 'bg-purple-100 hover:border-purple-400',
                  },
                  {
                    icon: <FileCode className={`w-6 h-6 ${isDarkMode ? 'text-cyan-400' : 'text-cyan-600'}`} />,
                    title: 'File Analysis',
                    description: 'Detailed per-file complexity tracking and change type classification.',
                    color: isDarkMode ? 'bg-cyan-500/20 hover:border-cyan-500/50' : 'bg-cyan-100 hover:border-cyan-400',
                  },
                ].map((feature, idx) => (
                  <div key={idx} className={`${isDarkMode ? 'bg-slate-800/30 border-slate-700/50' : 'bg-white border-slate-200'} border rounded-xl p-6 ${feature.color} transition-all`}>
                    <div className={`w-12 h-12 ${feature.color} rounded-lg flex items-center justify-center mb-4`}>
                      {feature.icon}
                    </div>
                    <h3 className={`font-bold ${textPrimary} mb-2`}>{feature.title}</h3>
                    <p className={`text-sm ${textSecondary}`}>{feature.description}</p>
                  </div>
                ))}
              </div>
            </div>
          ) : (
            // Analysis Results Display
            <div className="space-y-8">
              {/* Header with Reset */}
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-3">
                    <CheckCircle2 className="w-6 h-6 text-emerald-400" />
                    <h2 className={`text-3xl font-black ${textPrimary}`}>Analysis Complete</h2>
                  </div>
                  <div className="flex items-center gap-3">
                    <a
                      href={analysisData.metadata.prUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className={`${isDarkMode ? 'text-indigo-400 hover:text-indigo-300' : 'text-indigo-600 hover:text-indigo-700'} font-medium flex items-center gap-2 group`}
                    >
                      {analysisData.metadata.title}
                      <ExternalLink className="w-4 h-4 group-hover:translate-x-0.5 group-hover:-translate-y-0.5 transition-transform" />
                    </a>
                  </div>
                  <div className={`flex items-center gap-4 mt-2 text-sm ${textSecondary}`}>
                    <span className="flex items-center gap-1.5">
                      <Github className="w-4 h-4" />
                      {analysisData.metadata.author}
                    </span>
                    <span>•</span>
                    <span>{formatDate(analysisData.metadata.createdAt)}</span>
                    <span>•</span>
                    <span className="flex items-center gap-1.5">
                      <GitBranch className="w-4 h-4" />
                      {analysisData.metadata.baseBranch} ← {analysisData.metadata.headBranch}
                    </span>
                  </div>
                </div>
              
              </div>

              {/* Key Metrics Overview */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <MetricCard
                  icon={<FileCode className={`w-5 h-5 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />}
                  label="Files Changed"
                  value={analysisData.metrics.totalFilesChanged}
                  description="Total number of files modified in this PR"
                  isDarkMode={isDarkMode}
                />
                <MetricCard
                  icon={<TrendingUp className="w-5 h-5 text-emerald-400" />}
                  label="Lines Changed"
                  value={`+${analysisData.metrics.linesAdded} / -${analysisData.metrics.linesDeleted}`}
                  description={`Net: ${analysisData.metrics.netLinesChanged > 0 ? '+' : ''}${analysisData.metrics.netLinesChanged}`}
                  isDarkMode={isDarkMode}
                />
                <MetricCard
                  icon={<Clock className="w-5 h-5 text-amber-400" />}
                  label="Review Time"
                  value={`${analysisData.difficulty.estimatedReviewMinutes}m`}
                  description="Estimated time for thorough review"
                  isDarkMode={isDarkMode}
                />
                <MetricCard
                  icon={<Network className={`w-5 h-5 ${isDarkMode ? 'text-purple-400' : 'text-purple-600'}`} />}
                  label="Blast Radius"
                  value={analysisData.blastRadius.scope}
                  description={`${analysisData.blastRadius.affectedModules} modules affected`}
                  isDarkMode={isDarkMode}
                />
              </div>

              {/* Risk and Difficulty Badges */}
              <div className="grid md:grid-cols-2 gap-6">
                <div className={`${isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200'} border rounded-xl p-6`}>
                  <h3 className={`text-sm font-semibold ${textSecondary} mb-4 flex items-center gap-2`}>
                    <AlertTriangle className="w-4 h-4" />
                    Risk Assessment
                    <InfoTooltip content="Overall risk score based on critical files, change patterns, and potential impact." isDarkMode={isDarkMode} />
                  </h3>
                  <RiskLevelBadge level={analysisData.risk.level} score={analysisData.risk.overallScore} isDarkMode={isDarkMode} />
                  {analysisData.risk.criticalFilesDetected.length > 0 && (
                    <div className={`mt-4 p-3 ${isDarkMode ? 'bg-rose-500/10 border-rose-500/20' : 'bg-rose-100 border-rose-300'} border rounded-lg`}>
                      <p className={`text-xs font-semibold ${isDarkMode ? 'text-rose-400' : 'text-rose-700'} mb-2`}>Critical Files Detected:</p>
                      <ul className={`text-xs ${isDarkMode ? 'text-slate-300' : 'text-slate-700'} space-y-1`}>
                        {analysisData.risk.criticalFilesDetected.map((file, idx) => (
                          <li key={idx} className="font-mono">{file}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>

                <div className={`${isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200'} border rounded-xl p-6`}>
                  <h3 className={`text-sm font-semibold ${textSecondary} mb-4 flex items-center gap-2`}>
                    <Brain className="w-4 h-4" />
                    Difficulty Assessment
                    <InfoTooltip content="Review complexity based on code size, spread, cognitive load, and context switching." isDarkMode={isDarkMode} />
                  </h3>
                  <DifficultyBadge level={analysisData.difficulty.level} minutes={analysisData.difficulty.estimatedReviewMinutes} isDarkMode={isDarkMode} />
                </div>
              </div>

              {/* Breakdown Charts */}
              <div className="grid md:grid-cols-2 gap-6">
                <BreakdownChart
                  title="Risk Breakdown"
                  breakdown={analysisData.risk.breakdown}
                  type="risk"
                  isDarkMode={isDarkMode}
                />
                <BreakdownChart
                  title="Difficulty Breakdown"
                  breakdown={analysisData.difficulty.breakdown}
                  type="difficulty"
                  isDarkMode={isDarkMode}
                />
              </div>

              {/* Mermaid Diagram - PLACE IT HERE */}
              {analysisData.mermaidDiagram && (
                <MermaidDiagram
                  diagram={analysisData.mermaidDiagram}
                  verificationNotes={analysisData.diagramVerificationNotes}
                  metrics={analysisData.diagramMetrics}
                  isDarkMode={isDarkMode}
                />
              )}

              {/* Blast Radius */}
              <div className={`${isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200'} border rounded-xl p-6`}>
                <h3 className={`text-lg font-bold ${textPrimary} mb-4 flex items-center gap-2`}>
                  <Network className={`w-5 h-5 ${isDarkMode ? 'text-purple-400' : 'text-purple-600'}`} />
                  Blast Radius Assessment
                </h3>
                <div className="grid md:grid-cols-3 gap-6">
                  <div>
                    <div className={`text-sm ${textSecondary} mb-1`}>Impact Scope</div>
                    <div className={`text-2xl font-bold ${textPrimary}`}>{analysisData.blastRadius.scope}</div>
                  </div>
                  <div>
                    <div className={`text-sm ${textSecondary} mb-1`}>Affected Directories</div>
                    <div className={`text-2xl font-bold ${textPrimary}`}>{analysisData.blastRadius.affectedDirectories}</div>
                  </div>
                  <div>
                    <div className={`text-sm ${textSecondary} mb-1`}>Affected Modules</div>
                    <div className={`text-2xl font-bold ${textPrimary}`}>{analysisData.blastRadius.affectedModules}</div>
                  </div>
                </div>
                {analysisData.blastRadius.impactedAreas.length > 0 && (
                  <div className="mt-4">
                    <div className={`text-sm ${textSecondary} mb-2`}>Impacted Areas</div>
                    <div className="flex flex-wrap gap-2">
                      {analysisData.blastRadius.impactedAreas.map((area, idx) => (
                        <span key={idx} className={`px-3 py-1 ${isDarkMode ? 'bg-purple-500/10 border-purple-500/30 text-purple-400' : 'bg-purple-100 border-purple-300 text-purple-700'} border rounded-full text-sm`}>
                          {area}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
                <div className={`mt-4 pt-4 border-t ${isDarkMode ? 'border-slate-700/50' : 'border-slate-200'}`}>
                  <p className={`text-sm ${isDarkMode ? 'text-slate-300' : 'text-slate-700'}`}>{analysisData.blastRadius.assessment}</p>
                </div>
              </div>

              {/* AI Narrative */}
              
              <NarrativeSection narrative={analysisData.narrative} isDarkMode={isDarkMode} />

              {/* File Changes */}
              <div className={`${isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200'} border rounded-xl p-6`}>
                <div className="flex items-center justify-between mb-6">
                  <h3 className={`text-lg font-bold ${textPrimary} flex items-center gap-2`}>
                    <FileCode className={`w-5 h-5 ${isDarkMode ? 'text-indigo-400' : 'text-indigo-600'}`} />
                    File Changes ({analysisData.metrics.fileChanges.length})
                  </h3>
                  <div className="flex items-center gap-4 text-sm">
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-full bg-emerald-500/30 border border-emerald-500" />
                      <span className={textSecondary}>Added</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-full bg-amber-500/30 border border-amber-500" />
                      <span className={textSecondary}>Modified</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-full bg-rose-500/30 border border-rose-500" />
                      <span className={textSecondary}>Deleted</span>
                    </div>
                  </div>
                </div>
                <div className="space-y-2">
                  {analysisData.metrics.fileChanges.map((file, idx) => (
                    <FileChangeItem key={idx} file={file} isDarkMode={isDarkMode} />
                  ))}
                </div>
              </div>

              {/* File Type Distribution */}
              {Object.keys(analysisData.metrics.fileTypeDistribution).length > 0 && (
                <div className={`${isDarkMode ? 'bg-slate-800/50 border-slate-700/50' : 'bg-white border-slate-200'} border rounded-xl p-6`}>
                  <h3 className={`text-lg font-bold ${textPrimary} mb-4`}>File Type Distribution</h3>
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                    {Object.entries(analysisData.metrics.fileTypeDistribution).map(([type, count]) => (
                      <div key={type} className={`${isDarkMode ? 'bg-slate-900/50 border-slate-700/50' : 'bg-slate-50 border-slate-200'} rounded-lg p-4 border`}>
                        <div className={`text-2xl font-bold ${textPrimary} mb-1`}>{count}</div>
                        <div className={`text-sm ${textSecondary} uppercase`}>.{type}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Metadata */}
              <div className={`${isDarkMode ? 'bg-slate-800/30 border-slate-700/50' : 'bg-slate-100 border-slate-200'} border rounded-xl p-6`}>
                <h3 className={`text-sm font-semibold ${textSecondary} mb-4`}>Analysis Metadata</h3>
                <div className="grid md:grid-cols-2 gap-4 text-sm">
                  <div>
                    <span className={textSecondary}>Analysis ID:</span>
                    <span className={`ml-2 font-mono ${isDarkMode ? 'text-slate-300' : 'text-slate-700'}`}>{analysisData.analysisId}</span>
                  </div>
                  <div>
                    <span className={textSecondary}>Analyzed At:</span>
                    <span className={`ml-2 ${isDarkMode ? 'text-slate-300' : 'text-slate-700'}`}>{formatDate(analysisData.analyzedAt)}</span>
                  </div>
                  <div>
                    <span className={textSecondary}>Base SHA:</span>
                    <span className={`ml-2 font-mono ${isDarkMode ? 'text-slate-300' : 'text-slate-700'}`}>{analysisData.metadata.baseSha.substring(0, 8)}</span>
                  </div>
                  <div>
                    <span className={textSecondary}>Head SHA:</span>
                    <span className={`ml-2 font-mono ${isDarkMode ? 'text-slate-300' : 'text-slate-700'}`}>{analysisData.metadata.headSha.substring(0, 8)}</span>
                  </div>
                </div>
              </div>
            </div>
          )}
        </main>

        {/* Footer */}
        <footer className={`border-t ${borderColor} mt-20`}>
          <div className="max-w-7xl mx-auto px-6 py-8">
            <div className={`flex items-cente justify-between text-sm ${textSecondary}`}>
              <div className="flex items-center gap-2">
                <Shield className="w-4 h-4" />
                <span>ContextGuard © 2026</span>
              </div>
              <div className="flex items-center gap-6">
                <span>Powered by AI</span>
                <span>•</span>
                <span>Version 1.0.0</span>
              </div>
            </div>
          </div>
        </footer>
      </div>
    </div>
  );
};

export default ContextGuardDashboard;