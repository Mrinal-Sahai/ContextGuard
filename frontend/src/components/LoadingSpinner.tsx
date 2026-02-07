// src/components/LoadingSpinner.tsx

import React from 'react';
import './LoadingSpinner.css';

interface LoadingSpinnerProps {
  message?: string;
}

const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ message = 'Loading...' }) => {
  return (
    <div className="loading-spinner">
      <div className="spinner" />
      <p className="loading-message">{message}</p>
    </div>
  );
};

export default LoadingSpinner;