// src/components/ChangeMapSection.tsx

import React, { useState } from 'react';
import { type DiffMetrics } from '../types';
import FileTreeView from './FileTreeView';
import FileTypeChart from './FileTypeChart';
import './ChangeMapSection.css';

interface ChangeMapSectionProps {
  metrics: DiffMetrics;
}

/**
 * Change map section: File tree + type distribution.
 * 
 * WHY THIS EXISTS:
 * - Answers: "Where did the changes happen?"
 * - File tree with risk color-coding helps prioritize review
 * - Type distribution gives quick sense of change nature
 * 
 * DESIGN PRINCIPLE:
 * - Tree view is collapsible (reduce clutter)
 * - Files are color-coded by risk (red = critical, yellow = medium, green = low)
 * - Chart is simple (no fancy visualizations needed)
 */
const ChangeMapSection: React.FC<ChangeMapSectionProps> = ({ metrics }) => {
  return (
    <section className="change-map-section">
      <h2 className="section-title">Change Map</h2>

      {/* File tree with risk indicators */}
      <FileTreeView
        files={metrics.fileChanges}
        criticalFiles={metrics.criticalFiles}
      />

      {/* File type distribution */}
      <div className="file-type-section">
        <h3 className="subsection-title">File Types</h3>
        <FileTypeChart distribution={metrics.fileTypeDistribution} />
      </div>
    </section>
  );
};

export default ChangeMapSection;