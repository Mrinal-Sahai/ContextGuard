// src/components/ErrorMessage.tsx - UPDATED

import React from 'react';
import './ErrorMessage.css';

interface ErrorMessageProps {
  message: string;
  onBack?: () => void;
}

const ErrorMessage: React.FC<ErrorMessageProps> = ({ message, onBack }) => {
  const handleRetry = () => {
    window.location.reload();
  };

  const handleGoBack = () => {
    if (onBack) {
      onBack();
    } else {
      window.location.href = '/';
    }
  };

  return (
    <div className="error-message">
      <div className="error-icon">⚠️</div>
      <h2 className="error-title">Failed to Load PR Intelligence</h2>
      <p className="error-text">{message}</p>
      <div className="error-actions">
        <button onClick={handleRetry} className="retry-button">
          🔄 Retry
        </button>
        <button onClick={handleGoBack} className="back-button">
          ← Back to Home
        </button>
      </div>
    </div>
  );
};

export default ErrorMessage;