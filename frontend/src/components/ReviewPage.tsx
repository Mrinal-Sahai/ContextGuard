import { useParams } from "react-router-dom";
import { useEffect, useState } from "react";
import { MetricCard } from "./MetricCard";
import {
  Shield,
  Github,
  AlertTriangle,
  FileCode,
  GitBranch,
  Clock,
  TrendingUp,
  Brain,
  CheckCircle2,
  ExternalLink,
  Network,
  Sun,
  Moon
} from "lucide-react";

import MermaidDiagram from "./MermaidDiagram";
import { PRIntelligenceResponse } from "../types/index";
import { formatDate } from "../services/utility";
import { InfoTooltip } from "./InfoTooltip";
import { BreakdownChart } from "./BreakdownChart";
import { RiskLevelBadge } from "./RiskLevelBadge";
import { DifficultyBadge } from "./DifficultyBadge";
import { NarrativeSection } from "./NarrativeSection";
import { FileChangeItem } from "./FileChangeItem";

const API_BASE_URL =
  import.meta.env.VITE_API_URL ?? "http://localhost:8080/api/v1";

export default function ReviewPage() {
  const { analysisId } = useParams();

  const [analysisData, setAnalysisData] =
    useState<PRIntelligenceResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const [isDarkMode, setIsDarkMode] = useState(false);

  const textPrimary = isDarkMode ? "text-white" : "text-slate-900";
  const textSecondary = isDarkMode ? "text-slate-400" : "text-slate-600";

  const borderColor = isDarkMode
    ? "border-slate-800/50"
    : "border-slate-200";

  const bgPage = isDarkMode ? "bg-slate-950" : "bg-slate-50";
  const cardBg = isDarkMode
    ? "bg-slate-800/50 border-slate-700/50"
    : "bg-white border-slate-200";

  const cardBgSoft = isDarkMode
    ? "bg-slate-800/30 border-slate-700/50"
    : "bg-slate-100 border-slate-200";

  const innerCard = isDarkMode
    ? "bg-slate-900/50 border-slate-700/50"
    : "bg-white border-slate-200";

  useEffect(() => {
    if (!analysisId) {
      setError(true);
      setLoading(false);
      return;
    }

    const controller = new AbortController();

    const fetchAnalysis = async () => {
      try {
        const res = await fetch(
          `${API_BASE_URL}/pr-analysis/${analysisId}`,
          { signal: controller.signal }
        );

        if (!res.ok) throw new Error("API error");

        const json = await res.json();
        setAnalysisData(json);
      } catch (err: any) {
        if (err.name !== "AbortError") {
          console.error(err);
          setError(true);
        }
      } finally {
        setLoading(false);
      }
    };

    fetchAnalysis();

    return () => controller.abort();
  }, [analysisId]);

  if (loading)
    return (
      <div className={`flex items-center justify-center min-h-screen text-lg ${bgPage}`}>
        Loading analysis...
      </div>
    );

  if (error || !analysisData)
    return (
      <div className={`flex items-center justify-center min-h-screen text-lg ${bgPage}`}>
        Loading Analysis
      </div>
    );

  return (
    <div className={`${bgPage} min-h-screen`}>
      <div className="max-w-7xl mx-auto px-6 py-10 space-y-10">

        {/* Header */}
        <div className="flex items-start justify-between">
          <div className="flex-1">
            <div className="flex items-center gap-3 mb-3">
              <CheckCircle2 className="w-6 h-6 text-emerald-400" />
              <h2 className={`text-3xl font-black ${textPrimary}`}>
                Analysis Complete
              </h2>
            </div>

            <div className="flex items-center gap-3">
              <a
                href={analysisData.metadata?.prUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="text-indigo-500 hover:text-indigo-400 font-medium flex items-center gap-2 group"
              >
                {analysisData.metadata?.title}
                <ExternalLink className="w-4 h-4 group-hover:translate-x-0.5 group-hover:-translate-y-0.5 transition-transform" />
              </a>
            </div>

            <div className={`flex items-center gap-4 mt-2 text-sm ${textSecondary}`}>
              <span className="flex items-center gap-1.5">
                <Github className="w-4 h-4" />
                {analysisData.metadata?.author}
              </span>

              <span>•</span>

              <span>
                {analysisData.metadata?.createdAt
                  ? formatDate(analysisData.metadata.createdAt)
                  : ""}
              </span>

              <span>•</span>

              <span className="flex items-center gap-1.5">
                <GitBranch className="w-4 h-4" />
                {analysisData.metadata?.baseBranch} ←{" "}
                {analysisData.metadata?.headBranch}
              </span>
            </div>
          </div>

          {/* THEME TOGGLE */}
          <button
            onClick={() => setIsDarkMode(!isDarkMode)}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg border ${borderColor} ${textPrimary}`}
          >
            {isDarkMode ? <Sun size={16} /> : <Moon size={16} />}
            {isDarkMode ? "Light" : "Dark"}
          </button>
        </div>

        {/* Key Metrics */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          <MetricCard
            icon={<FileCode className="w-5 h-5 text-indigo-400" />}
            label="Files Changed"
            value={analysisData.metrics?.totalFilesChanged ?? 0}
            description="Total number of files modified"
            isDarkMode={isDarkMode}
          />

          <MetricCard
            icon={<TrendingUp className="w-5 h-5 text-emerald-400" />}
            label="Lines Changed"
            value={`+${analysisData.metrics?.linesAdded ?? 0} / -${
              analysisData.metrics?.linesDeleted ?? 0
            }`}
            description={`Net: ${analysisData.metrics?.netLinesChanged ?? 0}`}
            isDarkMode={isDarkMode}
          />

          <MetricCard
            icon={<Clock className="w-5 h-5 text-amber-400" />}
            label="Review Time"
            value={`${analysisData.difficulty?.estimatedReviewMinutes ?? 0}m`}
            description="Estimated review time"
            isDarkMode={isDarkMode}
          />

          <MetricCard
            icon={<Network className="w-5 h-5 text-purple-400" />}
            label="Blast Radius"
            value={analysisData.blastRadius?.scope ?? "-"}
            description={`${analysisData.blastRadius?.affectedModules ?? 0} modules`}
            isDarkMode={isDarkMode}
          />
        </div>

        {/* Risk + Difficulty */}
        <div className="grid md:grid-cols-2 gap-6">
          <div className={`border rounded-xl p-6 ${cardBg}`}>
            <h3 className={`text-sm font-semibold ${textSecondary} mb-4 flex items-center gap-2`}>
              <AlertTriangle className="w-4 h-4" />
              Risk Assessment
              <InfoTooltip
                content="Overall risk score based on critical files and impact."
                isDarkMode={isDarkMode}
              />
            </h3>

            <RiskLevelBadge
              level={analysisData.risk?.level}
              score={analysisData.risk?.overallScore}
              isDarkMode={isDarkMode}
            />
          </div>

          <div className={`border rounded-xl p-6 ${cardBg}`}>
            <h3 className={`text-sm font-semibold ${textSecondary} mb-4 flex items-center gap-2`}>
              <Brain className="w-4 h-4" />
              Difficulty Assessment
            </h3>

            <DifficultyBadge
              level={analysisData.difficulty?.level}
              minutes={analysisData.difficulty?.estimatedReviewMinutes}
              isDarkMode={isDarkMode}
            />
          </div>
        </div>

        {/* Breakdown Charts */}
        <div className="grid md:grid-cols-2 gap-6">
          <BreakdownChart
            title="Risk Breakdown"
            breakdown={analysisData.risk?.breakdown ?? {}}
            type="risk"
            isDarkMode={isDarkMode}
          />

          <BreakdownChart
            title="Difficulty Breakdown"
            breakdown={analysisData.difficulty?.breakdown ?? {}}
            type="difficulty"
            isDarkMode={isDarkMode}
          />
        </div>


        {/* Blast Radius */}
        <div className={`border rounded-xl p-6 ${cardBg}`}>
          <h3 className={`text-lg font-bold ${textPrimary} mb-4`}>
            Blast Radius Assessment
          </h3>

          <div className="grid md:grid-cols-3 gap-6">
            <div>
              <div className={`text-sm ${textSecondary}`}>Impact Scope</div>
              <div className={`text-2xl font-bold ${textPrimary}`}>
                {analysisData.blastRadius?.scope}
              </div>
            </div>

            <div>
              <div className={`text-sm ${textSecondary}`}>Directories</div>
              <div className={`text-2xl font-bold ${textPrimary}`}>
                {analysisData.blastRadius?.affectedDirectories}
              </div>
            </div>

            <div>
              <div className={`text-sm ${textSecondary}`}>Modules</div>
              <div className={`text-2xl font-bold ${textPrimary}`}>
                {analysisData.blastRadius?.affectedModules}
              </div>
            </div>
          </div>
        </div>

{analysisData.narrative && (
        <NarrativeSection
          narrative={analysisData.narrative}
          isDarkMode={isDarkMode}
        />
)}

       {/* Mermaid */}
        {analysisData.mermaidDiagram && (
          <MermaidDiagram
            diagram={analysisData.mermaidDiagram}
            verificationNotes={analysisData.diagramVerificationNotes}
            metrics={analysisData.diagramMetrics}
            isDarkMode={isDarkMode}
          />
        )}

        {/* File Changes */}
        <div className={`border rounded-xl p-6 ${cardBg}`}>
          <h3 className={`text-lg font-bold ${textPrimary} mb-4`}>
            File Changes ({analysisData.metrics?.fileChanges?.length ?? 0})
          </h3>

          <div className="space-y-2">
            {(analysisData.metrics?.fileChanges ?? []).map((file, idx) => (
              <FileChangeItem key={idx} file={file} isDarkMode={isDarkMode} />
            ))}
          </div>
        </div>

        {/* File Types */}
        {Object.keys(analysisData.metrics?.fileTypeDistribution ?? {}).length >
          0 && (
          <div className={`border rounded-xl p-6 ${cardBg}`}>
            <h3 className={`text-lg font-bold ${textPrimary} mb-4`}>
              File Type Distribution
            </h3>

            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {Object.entries(
                analysisData.metrics?.fileTypeDistribution ?? {}
              ).map(([type, count]) => (
                <div
                  key={type}
                  className={`border rounded-lg p-4 ${innerCard}`}
                >
                  <div className={`text-2xl font-bold ${textPrimary}`}>
                    {count}
                  </div>
                  <div className={`text-sm ${textSecondary}`}>.{type}</div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Metadata */}
        <div className={`border rounded-xl p-6 ${cardBgSoft}`}>
          <h3 className={`text-sm font-semibold ${textSecondary} mb-4`}>
            Analysis Metadata
          </h3>

          <div className="grid md:grid-cols-2 gap-4 text-sm">

            <div>
              <span className={textSecondary}>Analyzed At:</span>
              <span className= {`ml-2 ${textPrimary}`} >
                {formatDate(analysisData.analyzedAt)}
              </span>
            </div>
          </div>
        </div>

        {/* Footer */}
        <footer className={`border-t ${borderColor} pt-6`}>
          <div className="flex items-center justify-between text-sm">
            <div className={`flex items-center gap-2 ${textSecondary}`}>
              <Shield className="w-4 h-4" />
              ContextGuard © 2026
            </div>

            <div className={textSecondary}>
              Powered by AI • Version 1.0
            </div>
          </div>
        </footer>
      </div>
    </div>
  );
}