// src/components/DashboardHeader.tsx

import React from 'react';
import { type PRMetadata } from '../types';
import './DashboardHeader.css';

interface DashboardHeaderProps {
  metadata: PRMetadata;
  analysisId: string;
}

/**
 * Dashboard header with PR metadata and quick actions.
 * 
 * WHY THIS EXISTS:
 * - Provides context: What PR are we reviewing?
 * - Quick access to original PR on GitHub
 * - Export functionality for offline review
 * 
 * DESIGN PRINCIPLE:
 * - Compact (don't waste vertical space)
 * - Action-oriented (links are prominent)
 */
const DashboardHeader: React.FC<DashboardHeaderProps> = ({ metadata, analysisId }) => {
  const handleExportPDF = () => {
    // Trigger PDF export API call
    window.open(`/api/v1/pr-analysis/${analysisId}/pdf`, '_blank');
  };

  const copyShareLink = () => {
    const shareUrl = `${window.location.origin}?id=${analysisId}`;
    navigator.clipboard.writeText(shareUrl);
    alert('Link copied to clipboard!');
  };

  return (
    <header className="dashboard-header">
      <div className="pr-info">
        <div className="pr-title-row">
          <span className="github-icon">📊</span>
          <h1 className="pr-title">{metadata.title}</h1>
        </div>
        <div className="pr-meta">
          <span className="repo-name">{extractRepoName(metadata.prUrl)}</span>
          <span className="separator">•</span>
          <span className="author">by {metadata.author}</span>
          <span className="separator">•</span>
          <span className="timestamp">{formatTimestamp(metadata.createdAt)}</span>
          {metadata.updatedAt !== metadata.createdAt && (
            <>
              <span className="separator">•</span>
              <span className="updated">Updated {formatTimestamp(metadata.updatedAt)}</span>
            </>
          )}
        </div>
      </div>

      <div className="quick-actions">
        <button onClick={copyShareLink} className="action-btn">
          🔗 Copy Link
        </button>
        <a href={metadata.prUrl} target="_blank" rel="noopener noreferrer" className="action-btn primary">
          🔗 View on GitHub
        </a>
      </div>
    </header>
  );
};

// Helper functions
const extractRepoName = (prUrl: string): string => {
  const match = prUrl.match(/github\.com\/([^/]+\/[^/]+)/);
  return match ? match[1] : '';
};

const formatTimestamp = (timestamp: string): string => {
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

  if (diffDays === 0) return 'today';
  if (diffDays === 1) return 'yesterday';
  if (diffDays < 7) return `${diffDays} days ago`;
  return date.toLocaleDateString();
};

export default DashboardHeader;