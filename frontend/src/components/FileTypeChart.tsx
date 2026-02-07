// src/components/FileTypeChart.tsx

import React from 'react';
import './FileTypeChart.css';

interface FileTypeChartProps {
  distribution: Record<string, number>;
}

/**
 * Simple horizontal bar chart showing file type distribution.
 * 
 * WHY THIS EXISTS:
 * - Quick visual of what kind of changes were made (Java vs XML vs config)
 * - Helps reviewer set context (backend changes vs frontend vs config)
 * - No external charting library needed (pure CSS)
 * 
 * DESIGN PRINCIPLE:
 * - Horizontal bars are easier to read than pie charts
 * - Percentages help understand proportion
 * - Color-coded by file type for visual distinction
 * - Sorted by count (most common first)
 */
const FileTypeChart: React.FC<FileTypeChartProps> = ({ distribution }) => {
  // Calculate total files
  const totalFiles = Object.values(distribution).reduce((sum, count) => sum + count, 0);

  // Convert to array and sort by count (descending)
  const sortedTypes = Object.entries(distribution)
    .map(([type, count]) => ({
      type,
      count,
      percentage: (count / totalFiles) * 100,
    }))
    .sort((a, b) => b.count - a.count);

  // Get color for file type (for visual distinction)
  const getFileTypeColor = (fileType: string): string => {
    const colorMap: Record<string, string> = {
      // Backend languages
      java: '#f89820',
      kt: '#a97bff',
      scala: '#dc322f',
      go: '#00add8',
      py: '#3776ab',
      rb: '#cc342d',
      php: '#777bb4',
      
      // Frontend languages
      js: '#f7df1e',
      jsx: '#61dafb',
      ts: '#3178c6',
      tsx: '#3178c6',
      vue: '#42b883',
      
      // Styling
      css: '#1572b6',
      scss: '#cc6699',
      less: '#1d365d',
      
      // Markup
      html: '#e34c26',
      xml: '#f7931e',
      json: '#000000',
      yaml: '#cb171e',
      yml: '#cb171e',
      
      // Config/Build
      gradle: '#02303a',
      maven: '#c71a36',
      properties: '#6db33f',
      
      // Documentation
      md: '#083fa1',
      txt: '#808080',
      
      // Database
      sql: '#f29111',
      
      // Default
      unknown: '#6b7280',
    };

    return colorMap[fileType.toLowerCase()] || colorMap.unknown;
  };

  // Get file type icon (optional visual enhancement)
  const getFileTypeIcon = (fileType: string): string => {
    const iconMap: Record<string, string> = {
      java: '☕',
      js: '📜',
      jsx: '⚛️',
      ts: '📘',
      tsx: '⚛️',
      py: '🐍',
      xml: '📄',
      json: '📋',
      md: '📝',
      css: '🎨',
      sql: '🗄️',
    };

    return iconMap[fileType.toLowerCase()] || '📄';
  };

  return (
    <div className="file-type-chart">
      {sortedTypes.map(({ type, count, percentage }) => (
        <div key={type} className="file-type-row">
          {/* File type label */}
          <div className="file-type-label">
            <span className="file-type-icon">{getFileTypeIcon(type)}</span>
            <span className="file-type-name">{type.toUpperCase()}</span>
          </div>

          {/* Bar chart */}
          <div className="file-type-bar-container">
            <div
              className="file-type-bar"
              style={{
                width: `${percentage}%`,
                backgroundColor: getFileTypeColor(type),
              }}
            />
          </div>

          {/* Count and percentage */}
          <div className="file-type-stats">
            <span className="file-count">{count}</span>
            <span className="file-percentage">({Math.round(percentage)}%)</span>
          </div>
        </div>
      ))}

      {/* Total count */}
      <div className="file-type-total">
        Total: {totalFiles} {totalFiles === 1 ? 'file' : 'files'}
      </div>
    </div>
  );
};

export default FileTypeChart;