// src/components/DifficultyIndicator.tsx - UPDATED

import React from 'react';
import { type DifficultyAssessment, type RiskAssessment, type DiffMetrics } from '../types';
import ReviewerChecklist from './ReviewerChecklist';
import './DifficultyIndicator.css';

interface DifficultyIndicatorProps {
  difficulty: DifficultyAssessment;
  risk: RiskAssessment;
  metrics: DiffMetrics;
}

/**
 * Difficulty indicator component.
 * 
 * WHY THIS EXISTS:
 * - Communicates review time commitment
 * - Separate from risk (danger vs effort)
 * - Helps reviewers plan their time
 * 
 * DESIGN PRINCIPLE:
 * - Color-coded by difficulty level
 * - Shows estimated time prominently
 * - Similar visual style to risk score card (consistency)
 */
const DifficultyIndicator: React.FC<DifficultyIndicatorProps> = ({ difficulty,risk, metrics }) => {
  const getDifficultyColor = (): string => {
    switch (difficulty.level) {
      case 'TRIVIAL': return '#22c55e';
      case 'EASY': return '#84cc16';
      case 'MODERATE': return '#eab308';
      case 'HARD': return '#f97316';
      case 'VERY_HARD': return '#ef4444';
      default: return '#64748b';
    }
  };

  const formatLevel = (level: string): string => {
    return level.replace('_', ' ');
  };

  return (
    <div className="difficulty-indicator">
      <h3>Review Difficulty</h3>
      
      <div 
        className="difficulty-score" 
        data-level={difficulty.level}
        style={{ borderColor: getDifficultyColor() }}
      >
        <div 
          className="difficulty-level"
          style={{ color: getDifficultyColor() }}
        >
          {formatLevel(difficulty.level)}
        </div>
        
        <div className="difficulty-time">
          <span>⏱️</span>
          <span>~{difficulty.estimatedReviewMinutes} min</span>
        </div>
      </div>

      {/* Optional: Show breakdown */}
      {difficulty.breakdown && (
        <div className="difficulty-breakdown-hint">
          <small>
            Based on: size, file spread, complexity, and context switching
          </small>
        </div>
      )}
  <ReviewerChecklist risk={risk} metrics={metrics} />

    </div>

    
  );
};

export default DifficultyIndicator;