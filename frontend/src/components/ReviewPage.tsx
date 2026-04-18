import { useParams } from "react-router-dom";
import { useEffect, useState } from "react";
import { MetricCard } from "./MetricCard";
import { ASTMetricsPanel } from "./ASTMetricsPanel";
import { useRef } from "react";
import html2canvas from "html2canvas-pro";
import RiskDifficultyPanel from "./RiskDifficultyPanel";
import jsPDF from "jspdf";
import {
  Shield,
  Github,
  AlertTriangle,
  FileCode,
  GitBranch,
  Clock,
  TrendingUp,
  CheckCircle2,
  ExternalLink,
  Network,
  Sun,
  Moon,
  Layers,
  Globe2,
  Boxes,
  Radio,
} from "lucide-react";

import MermaidDiagram from "./MermaidDiagram";
import { PRIntelligenceResponse } from "../types/index";
import { formatDate } from "../services/utility";
import { NarrativeSection } from "./NarrativeSection";
import { FileChangeItem } from "./FileChangeItem";
import MergeReadinessBanner from "./MergeReadinessBanner";

const API_BASE_URL =
  import.meta.env.VITE_API_URL ?? "http://localhost:8080/api/v1";

export default function ReviewPage() {
  const { analysisId } = useParams();
  const reportRef = useRef<HTMLDivElement>(null);

  const [analysisData, setAnalysisData] =
    useState<PRIntelligenceResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const [isDarkMode, setIsDarkMode] = useState(false);
  const [loadingStage, setLoadingStage] = useState(0);

  const LOADING_STAGES = [
    { label: "Fetching PR metadata", ms: 1500 },
    { label: "Scoring risk & difficulty", ms: 3000 },
    { label: "Parsing AST call graph", ms: 10000 },
    { label: "Generating sequence diagram", ms: 6000 },
    { label: "AI narrative generation", ms: 5000 },
  ];

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

    // Advance loading stage labels while waiting for the response
    let stageIdx = 0;
    const advanceStage = () => {
      if (stageIdx < LOADING_STAGES.length - 1) {
        stageIdx++;
        setLoadingStage(stageIdx);
        stageTimer = setTimeout(advanceStage, LOADING_STAGES[stageIdx].ms);
      }
    };
    let stageTimer = setTimeout(advanceStage, LOADING_STAGES[0].ms);

    return () => {
      controller.abort();
      clearTimeout(stageTimer);
    };
  }, [analysisId]);


const generatePDF = async () => {
  console.log("PDF generation started");

  if (!reportRef.current) return;

  const element = reportRef.current;

  const canvas = await html2canvas(element, {
    scale: 2,
    useCORS: true,
    backgroundColor: "#ffffff",
    ignoreElements: (el) => {
      return el.classList?.contains("no-print");
    }
  });

  const imgData = canvas.toDataURL("image/png");

  const pdf = new jsPDF("p", "mm", "a4");

  const imgWidth = 210;
  const pageHeight = 295;

  const imgHeight = (canvas.height * imgWidth) / canvas.width;

  let heightLeft = imgHeight;
  let position = 0;

  pdf.addImage(imgData, "PNG", 0, position, imgWidth, imgHeight);
  heightLeft -= pageHeight;

  while (heightLeft > 0) {
    position = heightLeft - imgHeight;
    pdf.addPage();
    pdf.addImage(imgData, "PNG", 0, position, imgWidth, imgHeight);
    heightLeft -= pageHeight;
  }

  pdf.save(`review-report-${analysisId}.pdf`);
};

  if (loading)
    return (
      <div className={`flex items-center justify-center min-h-screen ${bgPage}`}>
        <div className="w-full max-w-sm space-y-6 px-6">
          <div className="text-center space-y-2">
            <div className="w-10 h-10 border-4 border-indigo-500 border-t-transparent rounded-full animate-spin mx-auto" />
            <p className={`text-base font-semibold ${textPrimary}`}>Analysing PR...</p>
          </div>
          <div className="space-y-2">
            {LOADING_STAGES.map((stage, idx) => (
              <div key={idx} className={`flex items-center gap-3 text-sm transition-opacity duration-500 ${idx > loadingStage ? "opacity-30" : "opacity-100"}`}>
                {idx < loadingStage ? (
                  <CheckCircle2 className="w-4 h-4 text-emerald-400 shrink-0" />
                ) : idx === loadingStage ? (
                  <div className="w-4 h-4 border-2 border-indigo-400 border-t-transparent rounded-full animate-spin shrink-0" />
                ) : (
                  <div className="w-4 h-4 rounded-full border border-slate-500 shrink-0" />
                )}
                <span className={idx === loadingStage ? "text-indigo-400 font-medium" : textSecondary}>
                  {stage.label}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>
    );

  if (error || !analysisData)
    return (
      <div className={`flex items-center justify-center min-h-screen ${bgPage}`}>
        <div className="text-center space-y-3">
          <AlertTriangle className="w-10 h-10 text-amber-400 mx-auto" />
          <p className={`text-lg font-semibold ${textPrimary}`}>Analysis not found</p>
          <p className={`text-sm ${textSecondary}`}>The analysis ID may be invalid or the result has expired.</p>
        </div>
      </div>
    );

  return (
    <div className={`${bgPage} min-h-screen`}>
       <div ref={reportRef}>
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
          <div className="flex items-center gap-3">
          <button
            onClick={() => setIsDarkMode(!isDarkMode)}
            className={`flex items-center gap-2 px-3 py-2 rounded-lg border ${borderColor} ${textPrimary}`}
          >
            {isDarkMode ? <Sun size={16} /> : <Moon size={16} />}
            {isDarkMode ? "Light" : "Dark"}
          </button>
          <button
            onClick={generatePDF}
            className={`flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 text-white hover:bg-indigo-700`}
          >
            Save Review Report
          </button>
        </div>
        </div>

        {/* Merge Readiness Verdict */}
        <MergeReadinessBanner
          risk={analysisData.risk}
          difficulty={analysisData.difficulty}
          semgrepFindingCount={analysisData.metrics?.semgrepFindingCount}
          highSeveritySastFindingCount={analysisData.metrics?.highSeveritySastFindingCount}
          astAccurate={analysisData.metrics?.astAccurate}
          isDarkMode={isDarkMode}
        />

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

        {/* Risk + Difficulty — full signal breakdown with formulas and research evidence */}
        <RiskDifficultyPanel
          risk={analysisData.risk}
          difficulty={analysisData.difficulty}
          isDarkMode={isDarkMode}
        />


        {/* Blast Radius */}
        {analysisData.blastRadius && <BlastRadiusCard blastRadius={analysisData.blastRadius} isDarkMode={isDarkMode} cardBg={cardBg} textPrimary={textPrimary} textSecondary={textSecondary} />}

        {<ASTMetricsPanel metrics={analysisData.metrics} isDarkMode={isDarkMode}/>}



{analysisData.narrative && (
        <NarrativeSection
          narrative={analysisData.narrative}
          isDarkMode={isDarkMode}
        />
)}

        {/* Call Graph Diagram */}
        {analysisData.mermaidDiagram ? (
          <MermaidDiagram
            diagram={analysisData.mermaidDiagram}
            verificationNotes={analysisData.diagramVerificationNotes}
            metrics={analysisData.diagramMetrics}
            isDarkMode={isDarkMode}
          />
        ) : (
          <div className={`border rounded-xl p-6 ${cardBg}`}>
            <h3 className={`text-lg font-bold ${textPrimary} mb-2`}>Call Graph Diagram</h3>
            <div className={`flex items-start gap-3 text-sm ${textSecondary}`}>
              <Network className="w-5 h-5 text-slate-400 shrink-0 mt-0.5" />
              <div>
                <p className="font-medium">Diagram not yet available</p>
                <p className="mt-1">
                  The sequence diagram is generated after AST parsing completes.
                  This happens asynchronously — refresh in a few seconds, or it may be unavailable
                  if the repository uses only unsupported languages (Ruby, plain JS without type info).
                </p>
                {analysisData.diagramMetrics && (
                  <p className="mt-2 text-xs">
                    Partial graph data: {analysisData.diagramMetrics.totalNodes} nodes,{" "}
                    {analysisData.diagramMetrics.totalEdges} edges, max depth {analysisData.diagramMetrics.maxDepth}
                  </p>
                )}
              </div>
            </div>
          </div>
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
    </div>
  );
}

// ─── Blast Radius Card ─────────────────────────────────────────────────────────

const SCOPE_META: Record<string, { color: string; bg: string; border: string; label: string; description: string }> = {
  LOCALIZED:    { color: "text-emerald-400", bg: "bg-emerald-500/10", border: "border-emerald-500/30", label: "Localized",    description: "Change is contained to a single module and layer. Minimal cross-component risk." },
  COMPONENT:    { color: "text-cyan-400",    bg: "bg-cyan-500/10",    border: "border-cyan-500/30",    label: "Component",    description: "Multiple layers within one module affected. Review for layer contract changes." },
  MODULE:       { color: "text-amber-400",   bg: "bg-amber-500/10",   border: "border-amber-500/30",   label: "Module",       description: "Crosses module boundaries. Downstream consumers of the changed module may be affected." },
  CROSS_MODULE: { color: "text-orange-400",  bg: "bg-orange-500/10",  border: "border-orange-500/30",  label: "Cross-Module", description: "Multiple modules affected. Integration test coverage across module boundaries is essential." },
  SYSTEM_WIDE:  { color: "text-rose-400",    bg: "bg-rose-500/10",    border: "border-rose-500/30",    label: "System-Wide",  description: "4+ modules or 3+ architectural layers touched. Full regression test suite required before merge." },
};

function BlastRadiusCard({ blastRadius, isDarkMode, cardBg, textPrimary, textSecondary }: {
  blastRadius: NonNullable<import('../types/index').PRIntelligenceResponse['blastRadius']>;
  isDarkMode: boolean; cardBg: string; textPrimary: string; textSecondary: string;
}) {
  const scopeKey = blastRadius.scope?.replace(/-/g, "_") ?? "LOCALIZED";
  const meta = SCOPE_META[scopeKey] ?? SCOPE_META["LOCALIZED"];
  const cellBg = isDarkMode ? "bg-slate-900/50 border-slate-700/50" : "bg-slate-50 border-slate-200";

  return (
    <div className={`border rounded-xl p-6 ${cardBg}`}>
      {/* Header */}
      <div className="flex items-center gap-2 mb-5">
        <Radio className={`w-5 h-5 ${meta.color}`} />
        <h3 className={`text-sm font-semibold uppercase tracking-wider ${textSecondary}`}>
          Blast Radius Assessment
        </h3>
        <span className={`ml-auto px-2.5 py-0.5 rounded-full text-xs font-bold border ${meta.bg} ${meta.color} ${meta.border}`}>
          {meta.label}
        </span>
      </div>

      {/* Scope description */}
      <p className={`text-sm mb-5 ${textSecondary}`}>{meta.description}</p>

      {/* Stats grid */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-5">
        <div className={`rounded-lg p-3 border ${cellBg}`}>
          <div className={`text-xs mb-1 flex items-center gap-1 ${textSecondary}`}>
            <Boxes className="w-3.5 h-3.5" /> Modules
          </div>
          <div className={`text-2xl font-black ${meta.color}`}>{blastRadius.affectedModules ?? 0}</div>
          <div className={`text-xs mt-0.5 ${textSecondary}`}>
            {blastRadius.affectedModuleNames?.slice(0, 2).join(", ") || "—"}
          </div>
        </div>

        <div className={`rounded-lg p-3 border ${cellBg}`}>
          <div className={`text-xs mb-1 flex items-center gap-1 ${textSecondary}`}>
            <Layers className="w-3.5 h-3.5" /> Arch. Layers
          </div>
          <div className={`text-2xl font-black ${meta.color}`}>{blastRadius.affectedLayerCount ?? blastRadius.affectedLayers?.length ?? 0}</div>
          <div className={`text-xs mt-0.5 ${textSecondary}`}>
            {blastRadius.affectedLayers?.join(" → ") || "none"}
          </div>
        </div>

        <div className={`rounded-lg p-3 border ${cellBg}`}>
          <div className={`text-xs mb-1 flex items-center gap-1 ${textSecondary}`}>
            <Globe2 className="w-3.5 h-3.5" /> Domains
          </div>
          <div className={`text-2xl font-black ${meta.color}`}>{blastRadius.affectedDomains?.length ?? 0}</div>
          <div className={`text-xs mt-0.5 ${textSecondary}`}>
            {blastRadius.affectedDomains?.join(", ") || "—"}
          </div>
        </div>

        <div className={`rounded-lg p-3 border ${cellBg}`}>
          <div className={`text-xs mb-1 flex items-center gap-1 ${textSecondary}`}>
            <Network className="w-3.5 h-3.5" /> Directories
          </div>
          <div className={`text-2xl font-black ${textPrimary}`}>{blastRadius.affectedDirectories ?? 0}</div>
          <div className={`text-xs mt-0.5 ${textSecondary}`}>changed paths</div>
        </div>
      </div>

      {/* Reviewer guidance */}
      {blastRadius.reviewerGuidance && (
        <div className={`flex items-start gap-2 text-sm p-3 rounded-lg border ${meta.bg} ${meta.border}`}>
          <Radio className={`w-4 h-4 shrink-0 mt-0.5 ${meta.color}`} />
          <span className={meta.color}>{blastRadius.reviewerGuidance}</span>
        </div>
      )}
    </div>
  );
}