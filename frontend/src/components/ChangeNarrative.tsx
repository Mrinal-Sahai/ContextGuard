// src/components/ChangeNarrative.tsx

import React from 'react';
import { type AIGeneratedNarrative } from '../types';
import './ChangeNarrative.css';

interface ChangeNarrativeProps {
  narrative: AIGeneratedNarrative;
}

/**
 * AI-generated key changes and concerns.
 * 
 * WHY THIS EXISTS:
 * - Provides human-readable context (complements metrics)
 * - Highlights potential issues reviewer should watch for
 * - Saves reviewer from reading full PR description
 * 
 * DESIGN PRINCIPLE:
 * - Bullet points for scannability
 * - Concerns are visually distinct (warning color)
 * - AI disclaimer is present but subtle
 */
const ChangeNarrative: React.FC<ChangeNarrativeProps> = ({ narrative }) => {
  const parseListItems = (text: string): string[] => {
    return text
      .split('\n')
      .map(line => line.trim())
      .filter(line => line.startsWith('-') || line.startsWith('•'))
      .map(line => line.replace(/^[-•]\s*/, ''));
  };

  const keyChanges = parseListItems(narrative.keyChanges);
  const concerns = parseListItems(narrative.potentialConcerns);

  return (
    <section className="change-narrative">
      <h2 className="section-title">Key Changes & Concerns</h2>

      <div className="narrative-grid">
        {/* Key Changes */}
        <div className="narrative-column">
          <h3 className="subsection-title">What Changed:</h3>
          <ul className="narrative-list">
            {keyChanges.map((item, index) => (
              <li key={index} className="narrative-item">{item}</li>
            ))}
          </ul>
        </div>

        {/* Potential Concerns */}
        <div className="narrative-column">
          <h3 className="subsection-title">Potential Concerns:</h3>
          <ul className="narrative-list concerns">
            {concerns.map((item, index) => (
              <li key={index} className="narrative-item concern">
                <span className="concern-icon">⚠️</span>
                {item}
              </li>
            ))}
          </ul>
        </div>
      </div>

      <div className="ai-disclaimer">{narrative.disclaimer}</div>
    </section>
  );
};

export default ChangeNarrative;