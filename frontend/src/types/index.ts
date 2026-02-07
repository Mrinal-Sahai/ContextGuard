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
  keyChanges: string;
  potentialConcerns: string;
  generatedAt: string;
  disclaimer: string;
}