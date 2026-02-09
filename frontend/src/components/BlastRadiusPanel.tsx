// src/components/BlastRadiusPanel.tsx

import React from 'react';
import { type BlastRadiusAssessment } from '../types';
import './BlastRadiusPanel.css';

interface BlastRadiusPanelProps {
  blastRadius: BlastRadiusAssessment;
}

/**
 * Blast radius visualization component.
 * 
 * WHY THIS EXISTS:
 * - Shows scope of PR impact (localized vs system-wide)
 * - Identifies affected modules and functional areas
 * - Helps reviewer understand coordination requirements
 * 
 * DESIGN PRINCIPLE:
 * - Visual hierarchy: Scope icon/badge most prominent
 * - Metrics shown as stats cards
 * - Impacted areas listed for context
 */
const BlastRadiusPanel: React.FC<BlastRadiusPanelProps> = ({ blastRadius }) => {
  
  const getScopeConfig = (scope: string) => {
    switch (scope) {
      case 'LOCALIZED':
        return {
          icon: '🎯',
          color: '#22c55e',
          bgColor: '#dcfce7',
          label: 'Localized',
          description: 'Changes are contained to a single component'
        };
      case 'COMPONENT':
        return {
          icon: '📦',
          color: '#84cc16',
          bgColor: '#ecfccb',
          label: 'Component',
          description: 'Impact spans a single module'
        };
      case 'MODULE':
        return {
          icon: '🔗',
          color: '#eab308',
          bgColor: '#fef3c7',
          label: 'Module',
          description: 'Changes affect multiple related modules'
        };
      case 'SYSTEM_WIDE':
        return {
          icon: '🌐',
          color: '#ef4444',
          bgColor: '#fecaca',
          label: 'System-Wide',
          description: 'Broad impact across multiple systems'
        };
      default:
        return {
          icon: '❓',
          color: '#64748b',
          bgColor: '#f1f5f9',
          label: 'Unknown',
          description: 'Impact scope not determined'
        };
    }
  };

  const scopeConfig = getScopeConfig(blastRadius.scope);

  return (
    <section className="blast-radius-panel">
      <h2 className="section-title">Blast Radius</h2>

      {/* Scope Indicator */}
      <div 
        className="scope-indicator"
        style={{ 
          borderColor: scopeConfig.color,
          background: ` ${scopeConfig.bgColor}`
        }}
      >
        <div className="scope-icon" style={{ fontSize: '48px' }}>
          {scopeConfig.icon}
        </div>
        <div className="scope-info">
          <div 
            className="scope-label"
            style={{ color: scopeConfig.color }}
          >
            {scopeConfig.label}
          </div>
          <div className="scope-description">
            {scopeConfig.description}
          </div>
        </div>


        <div className="impact-metric-card">
          <div className="metric-icon">📁</div>
          <div className="metric-value">{blastRadius.affectedDirectories}</div>
          <div className="metric-label">
            {blastRadius.affectedDirectories === 1 ? 'Directory' : 'Directories'}
          </div>
        </div>

        <div className="impact-metric-card">
          <div className="metric-icon">📦</div>
          <div className="metric-value">{blastRadius.affectedModules}</div>
          <div className="metric-label">
            {blastRadius.affectedModules === 1 ? 'Module' : 'Modules'}
          </div>
        </div>
        </div>

      {/* Impacted Areas */}
      {blastRadius.impactedAreas.length > 0 && (
        <div className="impacted-areas">
          <h3 className="subsection-title">Impacted Areas</h3>
          <div className="area-tags">
            {blastRadius.impactedAreas.map((area, index) => (
              <span 
                key={index} 
                className="area-tag"
                style={{ borderColor: scopeConfig.color }}
              >
                {formatAreaName(area)}
              </span>
            ))}
          </div>
        </div>
      )}


      {/* Coordination Indicator (if system-wide) */}
      {blastRadius.scope === 'SYSTEM_WIDE' && (
        <div className="coordination-alert">
          <span className="alert-icon">⚠️</span>
          <span className="alert-text">
            High coordination required across teams
          </span>
        </div>
      )}
    </section>
  );
};

// Helper function to format area names
const formatAreaName = (area: string): string => {
  return area
    .split('-')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
};

export default BlastRadiusPanel;