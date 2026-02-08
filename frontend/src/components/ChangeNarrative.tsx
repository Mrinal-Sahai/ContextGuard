import React, { useState } from 'react';
import { type AIGeneratedNarrative } from '../types';
import './ChangeNarrative.css';

interface Props {
  narrative: AIGeneratedNarrative;
}

const ChangeNarrative: React.FC<Props> = ({ narrative }) => {
  const [activeTab, setActiveTab] = useState<'before' | 'after'>('after');

  const parseBullets = (text?: string): string[] =>
    text
      ? text
          .split('\n')
          .map(l => l.trim())
          .filter(l => l.startsWith('-'))
          .map(l => l.replace(/^-\s*/, ''))
      : [];

  const keyChanges = parseBullets(narrative.keyChanges);
  const concerns = parseBullets(narrative.potentialConcerns);

  return (
    <section className="change-narrative">
      {/* Header */}
      <header className="narrative-header">
        <h2>PR Change Summary</h2>
        {narrative.confidence && (
          <span className={`confidence-badge ${narrative.confidence.toLowerCase()}`}>
            Confidence: {narrative.confidence}
          </span>
        )}
      </header>

      {/* Overview */}
      {narrative.overview && (
        <section className="overview">
          <h3>Intent & Scope</h3>
          <p>{narrative.overview}</p>
        </section>
      )}

      {/* Before / After */}
      {(narrative.beforeBehavior || narrative.afterBehavior) && (
        <section className="behavior-section">
          <div className="tab-header">
            <button
              className={activeTab === 'before' ? 'active' : ''}
              onClick={() => setActiveTab('before')}
            >
              Before
            </button>
            <button
              className={activeTab === 'after' ? 'active' : ''}
              onClick={() => setActiveTab('after')}
            >
              After
            </button>
          </div>

          <div className="tab-content">
            {activeTab === 'before' && (
              <p>{narrative.beforeBehavior || 'Not determinable from provided context.'}</p>
            )}
            {activeTab === 'after' && (
              <p>{narrative.afterBehavior || 'Not determinable from provided context.'}</p>
            )}
          </div>
        </section>
      )}

      {/* Key Changes */}
      <section className="changes-section">
        <h3>Key Changes</h3>
        {keyChanges.length > 0 ? (
          <ul>
            {keyChanges.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        ) : (
          <p className="muted">No key changes identified.</p>
        )}
      </section>

      {/* Concerns */}
      <section className="concerns-section">
        <h3>Potential Concerns</h3>
        {concerns.length > 0 ? (
          <ul>
            {concerns.map((c, i) => (
              <li key={i}>
                <span className="warning-icon">⚠️</span>
                {c}
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted">No significant concerns flagged.</p>
        )}
      </section>

      {/* Disclaimer */}
      {narrative.disclaimer && (
        <footer className="disclaimer">
          {narrative.disclaimer}
        </footer>
      )}
    </section>
  );
};

export default ChangeNarrative;
