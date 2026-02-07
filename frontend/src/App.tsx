// src/App.tsx - UPDATED WITH ROUTING

import React from 'react';
import PRAnalysisLandingPage from './components/PRAnalysisLandingPage';
import PRIntelligenceDashboard from './components/ContextGuardDashBoard';
import ErrorBoundary from './components/ErrorBoundary';
import './App.css';

/**
 * Main app with simple routing.
 * 
 * Routes:
 * - / → Landing page (PR URL input)
 * - /dashboard?id={uuid} → Analysis results
 */
const App: React.FC = () => {
  const currentPath = window.location.pathname;
  const searchParams = new URLSearchParams(window.location.search);
  const hasAnalysisId = searchParams.has('id');

  // Simple routing logic
  const renderContent = () => {
    if (currentPath === '/dashboard' && hasAnalysisId) {
      return <PRIntelligenceDashboard />;
    }
    return <PRAnalysisLandingPage />;
  };

  return (
    <ErrorBoundary>
      <div className="app">
        {renderContent()}
      </div>
    </ErrorBoundary>
  );
};

export default App;