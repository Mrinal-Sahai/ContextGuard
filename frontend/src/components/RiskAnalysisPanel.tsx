// src/components/RiskAnalysisPanel.tsx

import React from 'react';
import { type RiskAssessment, type DiffMetrics } from '../types';
import RiskBreakdownChart from './RiskBreakdownChart';
import './RiskAnalysisPanel.css';

interface RiskAnalysisPanelProps {
  risk: RiskAssessment;
  metrics: DiffMetrics;
}

/**
 * Risk breakdown and critical file alerts.
 * 
 * WHY THIS EXISTS:
 * - Explains HOW the risk score was calculated (transparency)
 * - Highlights critical files that need extra attention
 * - Shows complexity delta to indicate logic changes
 * 
 * DESIGN PRINCIPLE:
 * - Breakdown chart shows contribution by factor
 * - Critical files are prominently displayed (alert box)
 * - Numbers are rounded for readability
 */
const RiskAnalysisPanel: React.FC<RiskAnalysisPanelProps> = ({ risk, metrics }) => {
  return (
    <section className="risk-analysis-panel">
      <h2 className="section-title">Risk Breakdown</h2>

      {/* Risk factor contributions */}
      <RiskBreakdownChart breakdown={risk.breakdown} />

      {/* Critical files alert */}
      {risk.criticalFilesDetected.length > 0 && (
        <div className="critical-files-alert">
          <div className="alert-header">
            <span className="alert-icon">⚠️</span>
            <span className="alert-title">CRITICAL FILES TOUCHED</span>
          </div>
          <ul className="critical-files-list">
            {risk.criticalFilesDetected.map(file => (
              <li key={file} className="critical-file">{file}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Complexity indicator */}
      <div className="complexity-indicator">
        <h3 className="subsection-title">Complexity Delta</h3>
        <div className="complexity-value">
          {metrics.complexityDelta > 0 ? '+' : ''}{metrics.complexityDelta}
        </div>
        <div className="complexity-description">
          {Math.abs(metrics.complexityDelta)} {metrics.complexityDelta > 0 ? 'new' : 'removed'} decision points
        </div>
      </div>
    </section>
  );
};

export default RiskAnalysisPanel;