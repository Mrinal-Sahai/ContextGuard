// src/components/PRAnalysisLandingPage.tsx

import React, { useState } from 'react';
import { analyzePR } from '../services/api';
import { type AnalyzePRResponse } from '../types';
import './PRAnalysisLandingPage.css';

/**
 * Landing page for PR analysis.
 * 
 * USER FLOW:
 * 1. User pastes GitHub PR URL
 * 2. Clicks "Analyze PR"
 * 3. Shows loading state while backend processes
 * 4. Redirects to dashboard with analysis ID
 * 
 * WHY THIS EXISTS:
 * - Clear entry point (no confusion about what to do)
 * - Validates URL before API call
 * - Provides immediate feedback during analysis
 */
const PRAnalysisLandingPage: React.FC = () => {
  const [prUrl, setPrUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const validateGitHubUrl = (url: string): boolean => {
    const pattern = /^https:\/\/github\.com\/[\w-]+\/[\w-]+\/pull\/\d+$/;
    return pattern.test(url);
  };

  const handleAnalyze = async () => {
  setError(null);

  if (!prUrl.trim()) {
    setError('Please enter a GitHub PR URL');
    return;
  }

  if (!validateGitHubUrl(prUrl)) {
    setError(
      'Invalid GitHub PR URL format. Expected: https://github.com/owner/repo/pull/123'
    );
    return;
  }

  setLoading(true);

  try {
    const response: AnalyzePRResponse = await analyzePR(prUrl);

    if (!response.success) {
      throw new Error(response.error || 'PR analysis failed');
    }

    const { analysisId } = response.data;

    if (!analysisId) {
      throw new Error('Analysis ID missing in response');
    }

    window.location.href = `/dashboard?id=${analysisId}`;
  } catch (err) {
    setError(err instanceof Error ? err.message : 'Failed to analyze PR');
    setLoading(false);
  }
};


  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !loading) {
      handleAnalyze();
    }
  };

  return (
    <div className="landing-page">
      <div className="landing-container">
        
        {/* Hero Section */}
        <div className="hero-section">
          <div className="logo">
            <span className="logo-icon">🔍</span>
            <h1 className="logo-text">Context Guard</h1>
          </div>
          
          <p className="tagline">
            Understand any GitHub Pull Request in 60 seconds
          </p>
          
          <p className="description">
            Get AI-powered risk analysis, complexity metrics, and actionable review checklists
          </p>
        </div>

        {/* Input Section */}
        <div className="input-section">
          <div className="input-container">
            <input
              type="text"
              className={`pr-url-input ${error ? 'error' : ''}`}
              placeholder="https://github.com/owner/repo/pull/123"
              value={prUrl}
              onChange={(e) => setPrUrl(e.target.value)}
              onKeyPress={handleKeyPress}
              disabled={loading}
            />
            
            <button
              className="analyze-button"
              onClick={handleAnalyze}
              disabled={loading || !prUrl.trim()}
            >
              {loading ? (
                <>
                  <span className="spinner-small" />
                  Analyzing...
                </>
              ) : (
                <>
                  <span className="button-icon">⚡</span>
                  Analyze PR
                </>
              )}
            </button>
          </div>

          {error && (
            <div className="error-message-inline">
              <span className="error-icon">⚠️</span>
              {error}
            </div>
          )}

          <div className="input-hints">
            <p className="hint-text">
              💡 Paste any public GitHub pull request URL to get started
            </p>
          </div>
        </div>

        {/* Features Section */}
        <div className="features-grid">
          <div className="feature-card">
            <div className="feature-icon">📊</div>
            <h3 className="feature-title">Risk Scoring</h3>
            <p className="feature-description">
              Deterministic risk assessment based on change volume, complexity, and critical files
            </p>
          </div>

          <div className="feature-card">
            <div className="feature-icon">🗺️</div>
            <h3 className="feature-title">Change Mapping</h3>
            <p className="feature-description">
              Visual file tree with color-coded risk indicators for quick navigation
            </p>
          </div>

          <div className="feature-card">
            <div className="feature-icon">🤖</div>
            <h3 className="feature-title">AI Summaries</h3>
            <p className="feature-description">
              Natural language explanations of what changed and potential concerns
            </p>
          </div>

          <div className="feature-card">
            <div className="feature-icon">✅</div>
            <h3 className="feature-title">Review Checklist</h3>
            <p className="feature-description">
              Auto-generated action items based on PR characteristics
            </p>
          </div>
        </div>

        {/* Example Section */}
        <div className="example-section">
          <p className="example-label">Try an example:</p>
          <button
            className="example-button"
            onClick={() => setPrUrl('https://github.com/spring-projects/spring-boot/pull/39876')}
            disabled={loading}
          >
            Spring Boot PR #39876
          </button>
        </div>

      </div>
    </div>
  );
};

export default PRAnalysisLandingPage;