// src/services/api.ts

import { type PRIntelligenceData, type AnalyzePRResponse } from "../types";

/**
 * API service for fetching PR intelligence from backend.
 * 
 * WHY THIS EXISTS:
 * - Centralizes all API calls
 * - Provides typed responses
 * - Handles errors consistently
 */



const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';
/**
 * Fetch PR intelligence by analysis ID.
 * 
 * Endpoint: GET /api/v1/pr-analysis/{analysisId}
 */
export const fetchPRIntelligence = async (analysisId: string): Promise<PRIntelligenceData> => {
  const response = await fetch(`${API_BASE_URL}/pr-analysis/${analysisId}`);

  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('PR analysis not found. The link may be invalid or expired.');
    }
    throw new Error(`Failed to fetch PR intelligence: ${response.statusText}`);
  }

  return response.json();
};

/**
 * Trigger new PR analysis (for future use).
 * 
 * Endpoint: POST /api/v1/pr-analysis/analyze
 */
export const analyzePR = async (
  prUrl: string
): Promise<AnalyzePRResponse> => {
  const response = await fetch(`${API_BASE_URL}/pr-analysis/analyze`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ prUrl }),
  });

  if (!response.ok) {
    throw new Error(`Failed to analyze PR: ${response.statusText}`);
  }

  return response.json();
};