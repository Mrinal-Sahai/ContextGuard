// src/types/index.ts

/**
 * Type definitions matching backend DTOs.
 * 
 * These types ensure type safety across the frontend.
 */

export interface PRIntelligenceData {
  analysisId: string;
  metadata: PRMetadata;
  metrics: DiffMetrics;
  risk: RiskAssessment;
  narrative: AIGeneratedNarrative;
  difficulty: DifficultyAssessment;
  blastRadius?: BlastRadiusAssessment;
  analyzedAt: string;
}

export type AnalyzePRResponse = {
  success: boolean;
  data: {
    analysisId: string;
    cached: boolean;
    message: string;
  };
  message: string;
  error: string | null;
};


export interface PRMetadata {
  title: string;
  author: string;
  createdAt: string;
  updatedAt: string;
  baseBranch: string;
  headBranch: string;
  prUrl: string;
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
}

export interface FileChangeSummary {
  filename: string;
  changeType: string;
  linesAdded: number;
  linesDeleted: number;
  complexityDelta: number;
   riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface RiskAssessment {
  overallScore: number;
  level: RiskLevel;
  breakdown: RiskBreakdown;
  criticalFilesDetected: string[];
}

export const RiskLevel = {
  LOW: 'LOW',
  MEDIUM: 'MEDIUM',
  HIGH: 'HIGH',
  CRITICAL: 'CRITICAL',
} as const;

export type RiskLevel = typeof RiskLevel[keyof typeof RiskLevel];


export interface RiskBreakdown {
  volumeContribution: number;
  complexityContribution: number;
  criticalPathContribution: number;
  churnContribution: number;
}

export interface AIGeneratedNarrative {
  overview: string;
  beforeBehavior?: string;
  afterBehavior?: string;
  keyChanges: string;
  potentialConcerns: string;
  confidence?: 'LOW' | 'MEDIUM' | 'HIGH';
  disclaimer?: string;
}


export interface DifficultyAssessment {
  overallScore: number;
  estimatedReviewMinutes: number;
  level: DifficultyLevel;
  breakdown: DifficultyBreakdown;
}

export interface DifficultyBreakdown {
  sizeContribution: number;        // Lines changed
  spreadContribution: number;      // Number of files
  cognitiveContribution: number;   // Complexity delta
  contextContribution: number;     // File type diversity
}

export const DifficultyLevel = {
  TRIVIAL: 'TRIVIAL',
  EASY: 'EASY',
  MODERATE: 'MODERATE',
  HARD: 'HARD',
  VERY_HARD: 'VERY_HARD',
} as const;

export type DifficultyLevel = typeof DifficultyLevel[keyof typeof DifficultyLevel];

export interface BlastRadiusAssessment {
  scope: 'LOCALIZED' | 'COMPONENT' | 'MODULE' | 'SYSTEM_WIDE';
  affectedDirectories: number;
  affectedModules: number;
  impactedAreas: string[];
  assessment: string;
}