
import React, { useEffect, useState } from 'react';
import { fetchPRIntelligence } from '../services/api';
import { type PRIntelligenceData } from '../types';
import DashboardHeader from './DashboardHeader';
import ExecutiveSummary from './ExecutiveSummary';
import ChangeMapSection from './ChangeMapSection';
import RiskAnalysisPanel from './RiskAnalysisPanel';
import ChangeNarrative from './ChangeNarrative';
import DiagramPanel from './DiagramPanel';
import LoadingSpinner from './LoadingSpinner';
import ErrorMessage from './ErrorMessage';
import BlastRadiusPanel from './BlastRadiusPanel';
import DifficultyIndicator from './DifficultyIndicator';
import './ContextGuardDashboard.css';

const PRIntelligenceDashboard: React.FC = () => {
  const [data, setData] = useState<PRIntelligenceData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const loadIntelligence = async () => {
      try {
        const urlParams = new URLSearchParams(window.location.search);
        const analysisId = urlParams.get('id');

        if (!analysisId) {
          throw new Error('No analysis ID provided in URL');
        }

        const intelligence = await fetchPRIntelligence(analysisId);
        setData(intelligence);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load PR intelligence');
      } finally {
        setLoading(false);
      }
    };

    loadIntelligence();
  }, []);

  if (loading) {
    return <LoadingSpinner message="Analyzing pull request..." />;
  }

  if (error || !data) {
    return (
      <ErrorMessage 
        message={error || 'No data available'} 
        onBack={() => window.location.href = '/'}
      />
    );
  }

  return (
    <div className="pr-intelligence-dashboard">
      <div className="dashboard-content">
        
        <DashboardHeader metadata={data.metadata} analysisId={data.analysisId} />\
        <ExecutiveSummary
          risk={data.risk}
          metrics={data.metrics}
          narrative={data.narrative}
        />

        
                <div className="main-content-grid">

          <ChangeMapSection metrics={data.metrics} />
          <RiskAnalysisPanel risk={data.risk} metrics={data.metrics} difficulty={data.difficulty} />
          {data.blastRadius && (<BlastRadiusPanel blastRadius={data.blastRadius} />)}
           <DifficultyIndicator difficulty={data.difficulty} risk={data.risk} metrics={data.metrics} />
           </div>

        <ChangeNarrative narrative={data.narrative} />   
        <DiagramPanel analysisId={data.analysisId} />     
      </div>
    </div>
  );
};

export default PRIntelligenceDashboard;