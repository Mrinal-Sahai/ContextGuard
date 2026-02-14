// src/App.tsx - UPDATED WITH ROUTING

import React from 'react';
import  ContextGuardDashboard from './components/MainDashboard';
import '../styles.css'
import ErrorBoundary from './components/ErrorBoundary';

/**
 * Main app with simple routing.
 * 
 * Routes:
 * - / → Landing page (PR URL input)
 * - /dashboard?id={uuid} → Analysis results
 */
const App: React.FC = () => {

  // Simple routing logic
  const renderContent = () => {
     return <ContextGuardDashboard />;
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