// src/App.tsx - UPDATED WITH ROUTING

import React from 'react';
import  ContextGuardDashboard from './components/MainDashboard';
import '../styles.css'
import ErrorBoundary from './components/ErrorBoundary';
import { BrowserRouter, Routes, Route } from "react-router-dom";
import ReviewPage from './components/ReviewPage';

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
    return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<ContextGuardDashboard />} />
        <Route path="/review/:analysisId" element={<ReviewPage />} />
      </Routes>
    </BrowserRouter>
  );
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