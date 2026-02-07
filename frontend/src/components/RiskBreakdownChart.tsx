// src/components/RiskBreakdownChart.tsx

import React from 'react';
import { type RiskBreakdown } from '../types';
import './RiskBreakdownChart.css';

interface RiskBreakdownChartProps {
  breakdown: RiskBreakdown;
}

/**
 * Simple horizontal bar chart showing risk factor contributions.
 * 
 * WHY THIS EXISTS:
 * - Makes risk scoring transparent (not a black box)
 * - Helps reviewer understand why risk is high
 * - No external charting library needed (pure CSS bars)
 * 
 * DESIGN PRINCIPLE:
 * - Horizontal bars are easier to read than pie charts
 * - Percentages are shown for clarity
 * - Color-coded bars (but not distracting)
 */
const RiskBreakdownChart: React.FC<RiskBreakdownChartProps> = ({ breakdown }) => {
  const factors = [
    { label: 'Volume', value: breakdown.volumeContribution, color: '#3b82f6' },
    { label: 'Complexity', value: breakdown.complexityContribution, color: '#8b5cf6' },
    { label: 'Critical Path', value: breakdown.criticalPathContribution, color: '#ef4444' },
    { label: 'Churn', value: breakdown.churnContribution, color: '#f59e0b' },
  ];

  const maxValue = Math.max(...factors.map(f => f.value));

  return (
    <div className="risk-breakdown-chart">
      {factors.map(factor => {
        const percentage = Math.round((factor.value / maxValue) * 100);

        return (
          <div key={factor.label} className="breakdown-row">
            <div className="breakdown-label">{factor.label}:</div>
            <div className="breakdown-bar-container">
              <div
                className="breakdown-bar"
                style={{
                  width: `${percentage}%`,
                  backgroundColor: factor.color,
                }}
              />
            </div>
            <div className="breakdown-value">{Math.round(factor.value * 100)}%</div>
          </div>
        );
      })}
    </div>
  );
};

export default RiskBreakdownChart;