// src/components/ExecutiveSummary.tsx - VISUAL IMPROVEMENTS

import React from 'react';
import { type RiskAssessment, type DiffMetrics, type AIGeneratedNarrative } from '../types';
import RiskScoreCard from './RiskScoreCard';
import './ExecutiveSummary.css';

interface ExecutiveSummaryProps {
  risk: RiskAssessment;
  metrics: DiffMetrics;
  narrative: AIGeneratedNarrative;
}

const ExecutiveSummary: React.FC<ExecutiveSummaryProps> = ({ risk, metrics, narrative }) => {
  return (
    <section className="executive-summary">
      <h2 className="section-title">Executive Summary</h2>

      <div className="summary-cards">
        {/* Risk Score - Most Important */}
        <RiskScoreCard risk={risk} />

        {/* Lines Added */}
        <div className="metric-card lines-added">
          <div className="metric-icon">➕</div>
          <div className="metric-value">{metrics.linesAdded}</div>
          <div className="metric-label">lines added</div>
        </div>

        {/* Lines Deleted */}
        <div className="metric-card lines-deleted">
          <div className="metric-icon">➖</div>
          <div className="metric-value">{metrics.linesDeleted}</div>
          <div className="metric-label">lines deleted</div>
        </div>

        {/* Files Changed */}
        <div className="metric-card files-changed">
          <div className="metric-icon">📁</div>
          <div className="metric-value">{metrics.totalFilesChanged}</div>
          <div className="metric-label">files changed</div>
        </div>

        {/* Complexity Delta */}
        <div className="metric-card complexity">
          <div className="metric-icon">🧩</div>
          <div className="metric-value">
            {metrics.complexityDelta > 0 ? '+' : ''}{metrics.complexityDelta}
          </div>
          <div className="metric-label">complexity delta</div>
        </div>
      </div>

      {/* AI Overview */}
      <div className="ai-overview">
        <div className="overview-header">
          <span className="ai-icon">🤖</span>
          <span className="ai-badge">AI-Generated Summary</span>
        </div>
        <p className="overview-text">{narrative.overview}</p>
      </div>
    </section>
  );
};

export default ExecutiveSummary;