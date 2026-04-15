// src/types/index.ts

export interface PRAnalysisRequest {
  prUrl: string;
  aiProvider: 'GEMINI' | 'OPENAI';
  githubToken?: string;
  aiToken?: string;
}

export interface PRAnalysisResponse {
  success: boolean;
  data: {
    analysisId: string;
    cached: boolean;
    message: string;
  };
  message: string;
  error: string | null;
}

export interface PRIntelligenceResponse {
  analysisId: string;
  metadata: PRMetadata;
  metrics: DiffMetrics;
  risk: RiskAssessment;
  difficulty: DifficultyAssessment;
  narrative: AIGeneratedNarrative;
  blastRadius: BlastRadiusAssessment;
  mermaidDiagram?: string;
  diagramVerificationNotes?: string;
  diagramMetrics?: DiagramMetrics;
  analyzedAt: string;
}

export interface DiagramMetrics {
  totalNodes: number;
  totalEdges: number;
  maxDepth: number;
  avgComplexity: number;
  hotspots: string[];
  callDistribution: Record<string, number>;
}

export interface PRMetadata {
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

export interface DiffMetrics {
  totalFilesChanged: number;
  linesAdded: number;
  linesDeleted: number;
  netLinesChanged: number;
  fileTypeDistribution: Record<string, number>;
  complexityDelta: number;
  criticalFiles: string[];
  fileChanges: FileChangeSummary[];
  // AST-derived fields — populated after FlowExtractorService runs
  maxCallDepth?: number;
  avgChangedMethodCC?: number;
  hotspotMethodIds?: string[];
  removedPublicMethods?: number;
  addedPublicMethods?: number;
  astAccurate?: boolean;
  semgrepFindingCount?: number;
}

export interface FileChangeSummary {
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

export interface CriticalDetectionResult {
  filename: string;
  score: number;
  reasons: string[];
  criticalityBand: CriticalityBand;
  isCritical: boolean;
}

export type CriticalityBand = 'LOW' | 'NOTABLE' | 'CRITICAL' | 'SEVERE';

export interface RiskAssessment {
  overallScore: number;
  level: RiskLevel;
  breakdown: RiskBreakdown;
  criticalFilesDetected: string[];
  reviewerGuidance?: string;
  primaryDriver?: string;
}

export interface RiskBreakdown {
  averageRiskContribution: number;
  peakRiskContribution: number;
  criticalPathDensityContribution: number;
  highRiskDensityContribution: number;
  complexityContribution?: number;
  testCoverageGapContribution?: number;
  sastFindingsContribution?: number;
  // raw values for tooltip display only — excluded from chart bars
  rawAverageRisk?: number;
  rawPeakRisk?: number;
  rawComplexityDelta?: number;
  rawCriticalDensity?: number;
  rawTestCoverageGap?: number;
  rawSastFindings?: number;
  signals?: SignalInterpretation[];
}

export interface SignalInterpretation {
  key: string;
  label: string;
  rawValue: number;
  unit: string;
  signalVerdict: string;
  whatItMeans: string;
  evidence: string;
  weight: number;
  normalizedSignal: number;
  weightedContribution: number;
}

export interface DifficultyAssessment {
  overallScore: number;
  level: DifficultyLevel;
  breakdown: DifficultyBreakdown;
  estimatedReviewMinutes: number;
  reviewerGuidance?: string;
}

export interface DifficultyBreakdown {
  sizeContribution: number;
  spreadContribution: number;
  cognitiveContribution: number;
  contextContribution: number;
  concentrationContribution: number;
  criticalImpactContribution: number;
  // raw values for tooltip display only — excluded from chart bars
  rawCognitiveDelta?: number;
  rawLOC?: number;
  rawLayerCount?: number;
  rawDomainCount?: number;
  rawCriticalCount?: number;
}

export interface AIGeneratedNarrative {
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

export interface BlastRadiusAssessment {
  scope: 'LOCALIZED' | 'COMPONENT' | 'MODULE' | 'CROSS_MODULE' | 'SYSTEM_WIDE';
  affectedDirectories: number;
  affectedModules: number;
  impactedAreas: string[];
  assessment: string;
  affectedModuleNames?: string[];
  affectedLayers?: string[];
  affectedLayerCount?: number;
  affectedDomains?: string[];
  reviewerGuidance?: string;
}

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type DifficultyLevel = 'TRIVIAL' | 'EASY' | 'MODERATE' | 'HARD' | 'VERY_HARD';