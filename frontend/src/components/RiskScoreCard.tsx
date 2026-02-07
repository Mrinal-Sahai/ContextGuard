// src/components/RiskScoreCard.tsx

import React from 'react';
import { RiskLevel, type RiskAssessment } from '../types';
import './RiskScoreCard.css';

interface RiskScoreCardProps {
  risk: RiskAssessment;
}

/**
 * Large, color-coded risk score display.
 * 
 * WHY THIS EXISTS:
 * - Risk is the MOST IMPORTANT signal for reviewers
 * - Color-coding enables instant visual recognition
 * - Visual dots show score progression (like security rating)
 * 
 * COLOR SCHEME:
 * - LOW: Green (#22c55e)
 * - MEDIUM: Yellow (#eab308)
 * - HIGH: Orange (#f97316)
 * - CRITICAL: Red (#ef4444)
 */
const RiskScoreCard: React.FC<RiskScoreCardProps> = ({ risk }) => {
  const score = Math.round(risk.overallScore * 100);
  const levelClass = risk.level.toLowerCase();

  // Visual indicator dots (5 total, filled based on risk level)
  const getFilledDots = (): number => {
    switch (risk.level) {
      case RiskLevel.LOW: return 1;
      case RiskLevel.MEDIUM: return 2;
      case RiskLevel.HIGH: return 4;
      case RiskLevel.CRITICAL: return 5;
      default: return 0;
    }
  };

  const filledDots = getFilledDots();

  return (
    <div className={`risk-score-card ${levelClass}`}>
      <div className="risk-score-value">{score}</div>
      <div className="risk-dots">
        {[1, 2, 3, 4, 5].map(i => (
          <span
            key={i}
            className={`risk-dot ${i <= filledDots ? 'filled' : ''}`}
          />
        ))}
      </div>
      <div className="risk-level-label">{risk.level}</div>
    </div>
  );
};

export default RiskScoreCard;