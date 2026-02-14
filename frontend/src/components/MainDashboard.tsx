import React, { useState } from 'react';
import { Shield, Github, Zap, AlertTriangle, FileCode, GitBranch, Clock, TrendingUp, Target, Brain, CheckCircle2, Info, Loader2, ChevronDown, ChevronRight, ExternalLink, BarChart3, Network } from 'lucide-react';

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

interface PRAnalysisRequest {
  prUrl: string;
  aiProvider: 'GEMINI' | 'OPENAI';
  githubToken?: string;
  aiToken?: string;
}

interface PRAnalysisResponse {
  success: boolean;
  data: {
    analysisId: string;
    cached: boolean;
    message: string;
  };
  message: string;
  error: string | null;
}

interface PRIntelligenceResponse {
  analysisId: string;
  metadata: PRMetadata;
  metrics: DiffMetrics;
  risk: RiskAssessment;
  difficulty: DifficultyAssessment;
  narrative: AIGeneratedNarrative;
  blastRadius: BlastRadiusAssessment;
  analyzedAt: string;
}

interface PRMetadata {
  title: string;
  author: string;
  createdAt: string;
  updatedAt: string;
  baseBranch: string;
  headBranch: string;
  headSha: string;
  baseSha: string;
  headRepo: string;
  baseRepo: string;
  prUrl: string;
  body: string;
}

interface DiffMetrics {
  totalFilesChanged: number;
  linesAdded: number;
  linesDeleted: number;
  netLinesChanged: number;
  fileTypeDistribution: Record<string, number>;
  complexityDelta: number;
  criticalFiles: string[];
  fileChanges: FileChangeSummary[];
}

interface FileChangeSummary {
  filename: string;
  changeType: 'added' | 'modified' | 'deleted';
  linesAdded: number;
  linesDeleted: number;
  complexityDelta: number;
  totalComplexityBefore: number;
  totalComplexityAfter: number;
  riskLevel: RiskLevel;
  methodChanges: any[] | null;
  methodSignatures: string | null;
  beforeSnippet: string | null;
  afterSnippet: string | null;
  criticalDetectionResult: CriticalDetectionResult;
  reason: string | null;
}

interface CriticalDetectionResult {
  filename: string;
  score: number;
  reasons: string[];
  isCritical: boolean;
}

interface RiskAssessment {
  overallScore: number;
  level: RiskLevel;
  breakdown: RiskBreakdown;
  criticalFilesDetected: string[];
}

interface RiskBreakdown {
  averageRiskContribution: number;
  peakRiskContribution: number;
  criticalPathDensityContribution: number;
  highRiskDensityContribution: number;
}

interface DifficultyAssessment {
  overallScore: number;
  level: DifficultyLevel;
  breakdown: DifficultyBreakdown;
  estimatedReviewMinutes: number;
}

interface DifficultyBreakdown {
  sizeContribution: number;
  spreadContribution: number;
  cognitiveContribution: number;
  contextContribution: number;
  concentrationContribution: number;
  criticalImpactContribution: number;
}

interface AIGeneratedNarrative {
  overview: string;
  structuralImpact: string;
  behavioralChanges: string;
  riskInterpretation: string;
  reviewFocus: string;
  checklist: string;
  confidence: string;
  generatedAt: string;
  disclaimer: string;
}

interface BlastRadiusAssessment {
  scope: 'LOCALIZED' | 'COMPONENT' | 'MODULE' | 'SYSTEM_WIDE';
  affectedDirectories: number;
  affectedModules: number;
  impactedAreas: string[];
  assessment: string;
}

type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
type DifficultyLevel = 'TRIVIAL' | 'EASY' | 'MODERATE' | 'HARD' | 'VERY_HARD';

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

const getRiskColor = (level: RiskLevel): string => {
  const colors = {
    LOW: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20',
    MEDIUM: 'text-amber-400 bg-amber-500/10 border-amber-500/20',
    HIGH: 'text-orange-400 bg-orange-500/10 border-orange-500/20',
    CRITICAL: 'text-rose-400 bg-rose-500/10 border-rose-500/20',
  };
  return colors[level];
};

const getDifficultyColor = (level: DifficultyLevel): string => {
  const colors = {
    TRIVIAL: 'text-emerald-400 bg-emerald-500/10 border-emerald-500/20',
    EASY: 'text-cyan-400 bg-cyan-500/10 border-cyan-500/20',
    MODERATE: 'text-amber-400 bg-amber-500/10 border-amber-500/20',
    HARD: 'text-orange-400 bg-orange-500/10 border-orange-500/20',
    VERY_HARD: 'text-rose-400 bg-rose-500/10 border-rose-500/20',
  };
  return colors[level];
};

const formatDate = (dateStr: string): string => {
  return new Date(dateStr).toLocaleString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

// ============================================================================
// COMPONENTS
// ============================================================================

const MetricCard: React.FC<{
  icon: React.ReactNode;
  label: string;
  value: string | number;
  description?: string;
  trend?: 'up' | 'down' | 'neutral';
  className?: string;
}> = ({ icon, label, value, description, trend, className = '' }) => {
  return (
    <div className={`relative group ${className}`}>
      <div className="absolute inset-0 bg-gradient-to-br from-slate-700/50 to-slate-800/50 rounded-xl blur-xl opacity-0 group-hover:opacity-100 transition-opacity duration-500" />
      <div className="relative bg-slate-800/90 backdrop-blur-sm border border-slate-700/50 rounded-xl p-5 hover:border-slate-600/50 transition-all duration-300">
        <div className="flex items-start justify-between mb-3">
          <div className="p-2.5 bg-gradient-to-br from-indigo-500/20 to-purple-500/20 rounded-lg border border-indigo-500/30">
            {icon}
          </div>
          {trend && (
            <TrendingUp className={`w-4 h-4 ${trend === 'up' ? 'text-emerald-400' : 'text-rose-400'}`} />
          )}
        </div>
        <div className="space-y-1">
          <div className="text-2xl font-bold text-white">{value}</div>
          <div className="text-sm font-medium text-slate-400">{label}</div>
          {description && (
            <div className="text-xs text-slate-500 mt-2 pt-2 border-t border-slate-700/50">
              {description}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

const InfoTooltip: React.FC<{ content: string }> = ({ content }) => {
  const [show, setShow] = useState(false);
  return (
    <div className="relative inline-block ml-1.5">
      <Info
        className="w-4 h-4 text-slate-500 hover:text-indigo-400 cursor-help transition-colors"
        onMouseEnter={() => setShow(true)}
        onMouseLeave={() => setShow(false)}
      />
      {show && (
        <div className="absolute z-50 left-6 top-0 w-64 p-3 bg-slate-900 border border-slate-700 rounded-lg shadow-xl text-xs text-slate-300">
          {content}
          <div className="absolute left-0 top-2 w-2 h-2 bg-slate-900 border-l border-t border-slate-700 transform -translate-x-1 rotate-45" />
        </div>
      )}
    </div>
  );
};

const RiskLevelBadge: React.FC<{ level: RiskLevel; score: number }> = ({ level, score }) => {
  return (
    <div className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border ${getRiskColor(level)}`}>
      <AlertTriangle className="w-4 h-4" />
      <span className="font-semibold text-sm">{level}</span>
      <span className="text-xs opacity-75">({(score * 100).toFixed(1)}%)</span>
    </div>
  );
};

const DifficultyBadge: React.FC<{ level: DifficultyLevel; minutes: number }> = ({ level, minutes }) => {
  return (
    <div className={`inline-flex items-center gap-2 px-4 py-2 rounded-lg border ${getDifficultyColor(level)}`}>
      <Clock className="w-4 h-4" />
      <span className="font-semibold text-sm">{level.replace('_', ' ')}</span>
      <span className="text-xs opacity-75">(~{minutes}m)</span>
    </div>
  );
};

const FileChangeItem: React.FC<{ file: FileChangeSummary }> = ({ file }) => {
  const [expanded, setExpanded] = useState(false);

  const getChangeIcon = () => {
    if (file.changeType === 'added') return <span className="text-emerald-400">+</span>;
    if (file.changeType === 'deleted') return <span className="text-rose-400">-</span>;
    return <span className="text-amber-400">~</span>;
  };

  const getChangeColor = () => {
    if (file.changeType === 'added') return 'border-emerald-500/30 bg-emerald-500/5';
    if (file.changeType === 'deleted') return 'border-rose-500/30 bg-rose-500/5';
    return 'border-amber-500/30 bg-amber-500/5';
  };

  return (
    <div className={`border rounded-lg overflow-hidden ${getChangeColor()}`}>
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full px-4 py-3 flex items-center justify-between hover:bg-slate-800/30 transition-colors"
      >
        <div className="flex items-center gap-3 flex-1 min-w-0">
          {expanded ? <ChevronDown className="w-4 h-4 flex-shrink-0 text-slate-400" /> : <ChevronRight className="w-4 h-4 flex-shrink-0 text-slate-400" />}
          <FileCode className="w-4 h-4 flex-shrink-0 text-indigo-400" />
          <span className="font-mono text-sm text-slate-200 truncate">{file.filename}</span>
          {getChangeIcon()}
        </div>
        <div className="flex items-center gap-4 ml-4">
          <div className={`px-2 py-1 rounded text-xs font-medium ${getRiskColor(file.riskLevel)}`}>
            {file.riskLevel}
          </div>
          <div className="flex items-center gap-2 text-xs text-slate-400">
            <span className="text-emerald-400">+{file.linesAdded}</span>
            <span className="text-rose-400">-{file.linesDeleted}</span>
          </div>
        </div>
      </button>

      {expanded && (
        <div className="px-4 pb-4 space-y-3 border-t border-slate-700/50 bg-slate-900/30">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 pt-3">
            <div>
              <div className="text-xs text-slate-500 mb-1">Complexity Δ</div>
              <div className="text-sm font-semibold text-white">{file.complexityDelta > 0 ? '+' : ''}{file.complexityDelta}</div>
            </div>
            <div>
              <div className="text-xs text-slate-500 mb-1">Before</div>
              <div className="text-sm font-semibold text-white">{file.totalComplexityBefore}</div>
            </div>
            <div>
              <div className="text-xs text-slate-500 mb-1">After</div>
              <div className="text-sm font-semibold text-white">{file.totalComplexityAfter}</div>
            </div>
            <div>
              <div className="text-xs text-slate-500 mb-1">Detection Score</div>
              <div className="text-sm font-semibold text-white">{file.criticalDetectionResult.score}</div>
            </div>
          </div>

          {file.criticalDetectionResult.reasons.length > 0 && (
            <div>
              <div className="text-xs text-slate-500 mb-2">Detection Signals</div>
              <div className="space-y-1">
                {file.criticalDetectionResult.reasons.map((reason, idx) => (
                  <div key={idx} className="text-xs text-slate-400 flex items-start gap-2">
                    <span className="text-indigo-400">•</span>
                    <span>{reason}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const NarrativeSection: React.FC<{ narrative: AIGeneratedNarrative }> = ({ narrative }) => {
  return (
    <div className="bg-gradient-to-br from-slate-800/50 to-slate-900/50 border border-slate-700/50 rounded-xl p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-gradient-to-br from-purple-500/20 to-pink-500/20 rounded-lg border border-purple-500/30">
            <Brain className="w-5 h-5 text-purple-400" />
          </div>
          <div>
            <h3 className="text-lg font-bold text-white">AI-Generated Analysis</h3>
            <p className="text-xs text-slate-500">Confidence: {narrative.confidence}</p>
          </div>
        </div>
        <span className="text-xs text-slate-500">{formatDate(narrative.generatedAt)}</span>
      </div>

      <div className="space-y-4">
        <NarrativeBlock title="Overview" content={narrative.overview} />
        <NarrativeBlock title="Structural Impact" content={narrative.structuralImpact} />
        <NarrativeBlock title="Behavioral Changes" content={narrative.behavioralChanges} />
        <NarrativeBlock title="Risk Interpretation" content={narrative.riskInterpretation} />
        <NarrativeBlock title="Review Focus" content={narrative.reviewFocus} />
        <NarrativeBlock title="Checklist" content={narrative.checklist} />
      </div>

      <div className="pt-4 border-t border-slate-700/50">
        <p className="text-xs text-slate-500 italic">{narrative.disclaimer}</p>
      </div>
    </div>
  );
};

const NarrativeBlock: React.FC<{ title: string; content: string }> = ({ title, content }) => {
  return (
    <div>
      <h4 className="text-sm font-semibold text-indigo-400 mb-2">{title}</h4>
      <p className="text-sm text-slate-300 leading-relaxed whitespace-pre-line">{content}</p>
    </div>
  );
};

const BreakdownChart: React.FC<{ 
  title: string; 
  breakdown: Record<string, number>; 
  type: 'risk' | 'difficulty';
}> = ({ title, breakdown, type }) => {
  const maxValue = Math.max(...Object.values(breakdown));
  
  return (
    <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-5">
      <h4 className="text-sm font-semibold text-white mb-4 flex items-center gap-2">
        <BarChart3 className="w-4 h-4 text-indigo-400" />
        {title}
      </h4>
      <div className="space-y-3">
        {Object.entries(breakdown).map(([key, value]) => {
          const percentage = maxValue > 0 ? (value / maxValue) * 100 : 0;
          const label = key.replace(/([A-Z])/g, ' $1').replace(/^./, str => str.toUpperCase());
          
          return (
            <div key={key}>
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-xs text-slate-400">{label}</span>
                <span className="text-xs font-mono text-slate-300">{(value * 100).toFixed(1)}%</span>
              </div>
              <div className="h-2 bg-slate-900 rounded-full overflow-hidden">
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

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const ContextGuardDashboard: React.FC = () => {
  const [prUrl, setPrUrl] = useState('');
  const [aiProvider, setAiProvider] = useState<'GEMINI' | 'OPENAI'>('OPENAI');
  const [githubToken, setGithubToken] = useState('');
  const [aiToken, setAiToken] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [analysisId, setAnalysisId] = useState<string | null>(null);
  const [analysisData, setAnalysisData] = useState<PRIntelligenceResponse | null>(null);

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
      // Step 1: Trigger analysis
      const analyzeResponse = await fetch('http://localhost:8080/api/v1/pr-analysis/analyze', {
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

      setAnalysisId(analyzeResult.data.analysisId);

      // Step 2: Fetch full intelligence
      const intelligenceResponse = await fetch(
        `http://localhost:8080/api/v1/pr-analysis/${analyzeResult.data.analysisId}`,
        {
          method: 'GET',
          headers: { 'Content-Type': 'application/json' },
        }
      );

      if (!intelligenceResponse.ok) {
        throw new Error(`Failed to fetch analysis: ${intelligenceResponse.statusText}`);
      }

      const intelligenceData: PRIntelligenceResponse = await intelligenceResponse.json();
      setAnalysisData(intelligenceData);

    } catch (err: any) {
      setError(err.message || 'An error occurred during analysis');
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setPrUrl('');
    setAnalysisId(null);
    setAnalysisData(null);
    setError(null);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-white relative overflow-hidden">
      {/* Animated Background */}
      <div className="fixed inset-0 overflow-hidden pointer-events-none">
        <div className="absolute top-0 left-1/4 w-96 h-96 bg-indigo-500/10 rounded-full blur-3xl animate-pulse" />
        <div className="absolute bottom-0 right-1/4 w-96 h-96 bg-purple-500/10 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '1s' }} />
        <div className="absolute top-1/2 left-1/2 w-96 h-96 bg-cyan-500/5 rounded-full blur-3xl animate-pulse" style={{ animationDelay: '2s' }} />
      </div>

      {/* Grid Pattern Overlay */}
      <div className="fixed inset-0 pointer-events-none opacity-20" style={{
        backgroundImage: 'linear-gradient(rgba(99, 102, 241, 0.1) 1px, transparent 1px), linear-gradient(90deg, rgba(99, 102, 241, 0.1) 1px, transparent 1px)',
        backgroundSize: '50px 50px'
      }} />

      <div className="relative z-10">
        {/* Header */}
        <header className="border-b border-slate-800/50 backdrop-blur-sm bg-slate-900/30">
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
                  <p className="text-sm text-slate-400 font-medium">Intelligent PR Risk Analysis Platform</p>
                </div>
              </div>
              
              <div className="flex items-center gap-3">
                <div className="px-4 py-2 bg-slate-800/50 rounded-lg border border-slate-700/50">
                  <div className="flex items-center gap-2">
                    <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                    <span className="text-sm font-medium text-slate-300">System Ready</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </header>

        <main className="max-w-7xl mx-auto px-6 py-12">
          {!analysisData ? (
            // Landing Page / Input Form
            <div className="max-w-3xl mx-auto">
              <div className="text-center mb-12">
                <h2 className="text-5xl font-black mb-4 bg-gradient-to-r from-white via-slate-200 to-slate-400 bg-clip-text text-transparent">
                  Analyze Your Pull Request
                </h2>
                <p className="text-lg text-slate-400 max-w-2xl mx-auto">
                  Get comprehensive risk assessment, difficulty analysis, and AI-powered insights for your GitHub pull requests in seconds.
                </p>
              </div>

              <div className="bg-slate-800/30 backdrop-blur-xl border border-slate-700/50 rounded-2xl p-8 shadow-2xl">
                {/* PR URL Input */}
                <div className="mb-6">
                  <label className="block text-sm font-semibold text-slate-300 mb-2">
                    GitHub Pull Request URL
                  </label>
                  <div className="relative">
                    <Github className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-slate-500" />
                    <input
                      type="text"
                      value={prUrl}
                      onChange={(e) => setPrUrl(e.target.value)}
                      placeholder="https://github.com/owner/repo/pull/123"
                      className="w-full pl-12 pr-4 py-4 bg-slate-900/50 border border-slate-700 rounded-xl text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-all"
                    />
                  </div>
                  {error && (
                    <p className="mt-2 text-sm text-rose-400 flex items-center gap-2">
                      <AlertTriangle className="w-4 h-4" />
                      {error}
                    </p>
                  )}
                </div>

                {/* AI Provider Selection */}
                <div className="mb-6">
                  <label className="block text-sm font-semibold text-slate-300 mb-3">
                    AI Provider
                  </label>
                  <div className="grid grid-cols-2 gap-3">
                    <button
                      onClick={() => setAiProvider('OPENAI')}
                      className={`p-4 rounded-xl border-2 transition-all ${
                        aiProvider === 'OPENAI'
                          ? 'border-indigo-500 bg-indigo-500/10'
                          : 'border-slate-700 bg-slate-900/30 hover:border-slate-600'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <Zap className="w-5 h-5 text-indigo-400" />
                        <span className="font-semibold">OpenAI</span>
                      </div>
                    </button>
                    <button
                      onClick={() => setAiProvider('GEMINI')}
                      className={`p-4 rounded-xl border-2 transition-all ${
                        aiProvider === 'GEMINI'
                          ? 'border-indigo-500 bg-indigo-500/10'
                          : 'border-slate-700 bg-slate-900/30 hover:border-slate-600'
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <Zap className="w-5 h-5 text-purple-400" />
                        <span className="font-semibold">Gemini</span>
                      </div>
                    </button>
                  </div>
                </div>

                {/* Advanced Options */}
                <div className="mb-6">
                  <button
                    onClick={() => setShowAdvanced(!showAdvanced)}
                    className="text-sm text-slate-400 hover:text-indigo-400 transition-colors flex items-center gap-2"
                  >
                    {showAdvanced ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                    Advanced Options (Optional)
                  </button>
                  
                  {showAdvanced && (
                    <div className="mt-4 space-y-4 pl-6 border-l-2 border-slate-700">
                      <div>
                        <label className="block text-sm font-medium text-slate-400 mb-2">
                          GitHub Personal Access Token
                        </label>
                        <input
                          type="password"
                          value={githubToken}
                          onChange={(e) => setGithubToken(e.target.value)}
                          placeholder="ghp_xxxxxxxxxxxxx"
                          className="w-full px-4 py-3 bg-slate-900/50 border border-slate-700 rounded-lg text-white placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                        />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-400 mb-2">
                          AI Provider API Token
                        </label>
                        <input
                          type="password"
                          value={aiToken}
                          onChange={(e) => setAiToken(e.target.value)}
                          placeholder="sk-xxxxxxxxxxxxx"
                          className="w-full px-4 py-3 bg-slate-900/50 border border-slate-700 rounded-lg text-white placeholder-slate-600 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
                        />
                      </div>
                    </div>
                  )}
                </div>

                {/* Analyze Button */}
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
                <div className="bg-slate-800/30 border border-slate-700/50 rounded-xl p-6 hover:border-indigo-500/50 transition-all">
                  <div className="w-12 h-12 bg-indigo-500/20 rounded-lg flex items-center justify-center mb-4">
                    <AlertTriangle className="w-6 h-6 text-indigo-400" />
                  </div>
                  <h3 className="font-bold text-white mb-2">Risk Assessment</h3>
                  <p className="text-sm text-slate-400">Multi-dimensional risk scoring with critical file detection and blast radius analysis.</p>
                </div>
                <div className="bg-slate-800/30 border border-slate-700/50 rounded-xl p-6 hover:border-purple-500/50 transition-all">
                  <div className="w-12 h-12 bg-purple-500/20 rounded-lg flex items-center justify-center mb-4">
                    <Brain className="w-6 h-6 text-purple-400" />
                  </div>
                  <h3 className="font-bold text-white mb-2">AI Insights</h3>
                  <p className="text-sm text-slate-400">Comprehensive narrative analysis generated by advanced language models.</p>
                </div>
                <div className="bg-slate-800/30 border border-slate-700/50 rounded-xl p-6 hover:border-cyan-500/50 transition-all">
                  <div className="w-12 h-12 bg-cyan-500/20 rounded-lg flex items-center justify-center mb-4">
                    <FileCode className="w-6 h-6 text-cyan-400" />
                  </div>
                  <h3 className="font-bold text-white mb-2">File Analysis</h3>
                  <p className="text-sm text-slate-400">Detailed per-file complexity tracking and change type classification.</p>
                </div>
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
                    <h2 className="text-3xl font-black text-white">Analysis Complete</h2>
                  </div>
                  <div className="flex items-center gap-3">
                    <a
                      href={analysisData.metadata.prUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-indigo-400 hover:text-indigo-300 font-medium flex items-center gap-2 group"
                    >
                      {analysisData.metadata.title}
                      <ExternalLink className="w-4 h-4 group-hover:translate-x-0.5 group-hover:-translate-y-0.5 transition-transform" />
                    </a>
                  </div>
                  <div className="flex items-center gap-4 mt-2 text-sm text-slate-400">
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
                <button
                  onClick={handleReset}
                  className="px-6 py-3 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg font-semibold transition-all"
                >
                  New Analysis
                </button>
              </div>

              {/* Key Metrics Overview */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <MetricCard
                  icon={<FileCode className="w-5 h-5 text-indigo-400" />}
                  label="Files Changed"
                  value={analysisData.metrics.totalFilesChanged}
                  description="Total number of files modified in this PR"
                />
                <MetricCard
                  icon={<TrendingUp className="w-5 h-5 text-emerald-400" />}
                  label="Lines Changed"
                  value={`+${analysisData.metrics.linesAdded} / -${analysisData.metrics.linesDeleted}`}
                  description={`Net: ${analysisData.metrics.netLinesChanged > 0 ? '+' : ''}${analysisData.metrics.netLinesChanged}`}
                />
                <MetricCard
                  icon={<Clock className="w-5 h-5 text-amber-400" />}
                  label="Review Time"
                  value={`${analysisData.difficulty.estimatedReviewMinutes}m`}
                  description="Estimated time for thorough review"
                />
                <MetricCard
                  icon={<Network className="w-5 h-5 text-purple-400" />}
                  label="Blast Radius"
                  value={analysisData.blastRadius.scope}
                  description={`${analysisData.blastRadius.affectedModules} modules affected`}
                />
              </div>

              {/* Risk and Difficulty Badges */}
              <div className="grid md:grid-cols-2 gap-6">
                <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6">
                  <h3 className="text-sm font-semibold text-slate-400 mb-4 flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4" />
                    Risk Assessment
                    <InfoTooltip content="Overall risk score based on critical files, change patterns, and potential impact." />
                  </h3>
                  <RiskLevelBadge level={analysisData.risk.level} score={analysisData.risk.overallScore} />
                  {analysisData.risk.criticalFilesDetected.length > 0 && (
                    <div className="mt-4 p-3 bg-rose-500/10 border border-rose-500/20 rounded-lg">
                      <p className="text-xs font-semibold text-rose-400 mb-2">Critical Files Detected:</p>
                      <ul className="text-xs text-slate-300 space-y-1">
                        {analysisData.risk.criticalFilesDetected.map((file, idx) => (
                          <li key={idx} className="font-mono">{file}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>

                <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6">
                  <h3 className="text-sm font-semibold text-slate-400 mb-4 flex items-center gap-2">
                    <Brain className="w-4 h-4" />
                    Difficulty Assessment
                    <InfoTooltip content="Review complexity based on code size, spread, cognitive load, and context switching." />
                  </h3>
                  <DifficultyBadge level={analysisData.difficulty.level} minutes={analysisData.difficulty.estimatedReviewMinutes} />
                </div>
              </div>

              {/* Breakdown Charts */}
              <div className="grid md:grid-cols-2 gap-6">
                <BreakdownChart
                  title="Risk Breakdown"
                  breakdown={analysisData.risk.breakdown}
                  type="risk"
                />
                <BreakdownChart
                  title="Difficulty Breakdown"
                  breakdown={analysisData.difficulty.breakdown}
                  type="difficulty"
                />
              </div>

              {/* Blast Radius */}
              <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6">
                <h3 className="text-lg font-bold text-white mb-4 flex items-center gap-2">
                  <Network className="w-5 h-5 text-purple-400" />
                  Blast Radius Assessment
                </h3>
                <div className="grid md:grid-cols-3 gap-6">
                  <div>
                    <div className="text-sm text-slate-400 mb-1">Impact Scope</div>
                    <div className="text-2xl font-bold text-white">{analysisData.blastRadius.scope}</div>
                  </div>
                  <div>
                    <div className="text-sm text-slate-400 mb-1">Affected Directories</div>
                    <div className="text-2xl font-bold text-white">{analysisData.blastRadius.affectedDirectories}</div>
                  </div>
                  <div>
                    <div className="text-sm text-slate-400 mb-1">Affected Modules</div>
                    <div className="text-2xl font-bold text-white">{analysisData.blastRadius.affectedModules}</div>
                  </div>
                </div>
                {analysisData.blastRadius.impactedAreas.length > 0 && (
                  <div className="mt-4">
                    <div className="text-sm text-slate-400 mb-2">Impacted Areas</div>
                    <div className="flex flex-wrap gap-2">
                      {analysisData.blastRadius.impactedAreas.map((area, idx) => (
                        <span key={idx} className="px-3 py-1 bg-purple-500/10 border border-purple-500/30 rounded-full text-sm text-purple-400">
                          {area}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
                <div className="mt-4 pt-4 border-t border-slate-700/50">
                  <p className="text-sm text-slate-300">{analysisData.blastRadius.assessment}</p>
                </div>
              </div>

              {/* AI Narrative */}
              <NarrativeSection narrative={analysisData.narrative} />

              {/* File Changes */}
              <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6">
                <div className="flex items-center justify-between mb-6">
                  <h3 className="text-lg font-bold text-white flex items-center gap-2">
                    <FileCode className="w-5 h-5 text-indigo-400" />
                    File Changes ({analysisData.metrics.fileChanges.length})
                  </h3>
                  <div className="flex items-center gap-4 text-sm">
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-full bg-emerald-500/30 border border-emerald-500" />
                      <span className="text-slate-400">Added</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-full bg-amber-500/30 border border-amber-500" />
                      <span className="text-slate-400">Modified</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-full bg-rose-500/30 border border-rose-500" />
                      <span className="text-slate-400">Deleted</span>
                    </div>
                  </div>
                </div>
                <div className="space-y-2">
                  {analysisData.metrics.fileChanges.map((file, idx) => (
                    <FileChangeItem key={idx} file={file} />
                  ))}
                </div>
              </div>

              {/* File Type Distribution */}
              {Object.keys(analysisData.metrics.fileTypeDistribution).length > 0 && (
                <div className="bg-slate-800/50 border border-slate-700/50 rounded-xl p-6">
                  <h3 className="text-lg font-bold text-white mb-4">File Type Distribution</h3>
                  <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                    {Object.entries(analysisData.metrics.fileTypeDistribution).map(([type, count]) => (
                      <div key={type} className="bg-slate-900/50 rounded-lg p-4 border border-slate-700/50">
                        <div className="text-2xl font-bold text-white mb-1">{count}</div>
                        <div className="text-sm text-slate-400 uppercase">.{type}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Metadata */}
              <div className="bg-slate-800/30 border border-slate-700/50 rounded-xl p-6">
                <h3 className="text-sm font-semibold text-slate-400 mb-4">Analysis Metadata</h3>
                <div className="grid md:grid-cols-2 gap-4 text-sm">
                  <div>
                    <span className="text-slate-500">Analysis ID:</span>
                    <span className="ml-2 font-mono text-slate-300">{analysisData.analysisId}</span>
                  </div>
                  <div>
                    <span className="text-slate-500">Analyzed At:</span>
                    <span className="ml-2 text-slate-300">{formatDate(analysisData.analyzedAt)}</span>
                  </div>
                  <div>
                    <span className="text-slate-500">Base SHA:</span>
                    <span className="ml-2 font-mono text-slate-300">{analysisData.metadata.baseSha.substring(0, 8)}</span>
                  </div>
                  <div>
                    <span className="text-slate-500">Head SHA:</span>
                    <span className="ml-2 font-mono text-slate-300">{analysisData.metadata.headSha.substring(0, 8)}</span>
                  </div>
                </div>
              </div>
            </div>
          )}
        </main>

        {/* Footer */}
        <footer className="border-t border-slate-800/50 mt-20">
          <div className="max-w-7xl mx-auto px-6 py-8">
            <div className="flex items-center justify-between text-sm text-slate-500">
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