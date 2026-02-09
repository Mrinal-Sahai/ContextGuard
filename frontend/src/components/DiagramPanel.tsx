// src/components/DiagramPanel.tsx - ENHANCED

import React, { useEffect, useState, useRef } from 'react';
import mermaid from 'mermaid';
import './DiagramPanel.css';

interface DiagramPanelProps {
  analysisId: string;
}

interface DiagramResponse {
  analysisId: string;
  status: 'READY' | 'GENERATING' | 'FAILED';
  mermaid?: string;
  verificationNotes?: string;
  metrics?: GraphMetrics;
  error?: string;
  message?: string;
}

interface GraphMetrics {
  totalNodes: number;
  totalEdges: number;
  avgComplexity: number;
  hotspots: string[];
}

const DiagramPanel: React.FC<DiagramPanelProps> = ({ analysisId }) => {
  const [diagram, setDiagram] = useState<DiagramResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pollCount, setPollCount] = useState(0);
  const diagramRef = useRef<HTMLDivElement>(null);
  const pollIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const API_BASE_URL =import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';

  // Initialize Mermaid with enhanced config
  useEffect(() => {
    mermaid.initialize({
      startOnLoad: true,
      theme: 'base',
      securityLevel: 'loose',
      flowchart: {
        useMaxWidth: true,
        htmlLabels: true,
        curve: 'basis',
        padding: 20
      },
      themeVariables: {
        fontSize: '16px',
        fontFamily: 'system-ui, -apple-system, sans-serif'
      }
    });
  }, []);

  // Fetch diagram with polling
  useEffect(() => {
    const fetchDiagram = async () => {
      try {
        const response = await fetch(
          `${API_BASE_URL}/api/v1/pr-analysis/${analysisId}/diagram`
        );
        
        if (!response.ok) {
          throw new Error(`Failed to fetch diagram: ${response.statusText}`);
        }
        
        const data: DiagramResponse = await response.json();
        setDiagram(data);
        
        // If still generating, poll again
        if (data.status === 'GENERATING' && pollCount < 60) {
          setPollCount(prev => prev + 1);
          pollIntervalRef.current = setTimeout(fetchDiagram, 2000); // Poll every 2s
        } else if (data.status === 'READY' || data.status === 'FAILED') {
          setLoading(false);
        } else if (pollCount >= 60) {
          setError('Diagram generation timeout');
          setLoading(false);
        }
        
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load diagram');
        setLoading(false);
      }
    };

    fetchDiagram();

    return () => {
      if (pollIntervalRef.current) {
        clearTimeout(pollIntervalRef.current);
      }
    };
  }, [analysisId, pollCount]);

  // Render Mermaid diagram
  useEffect(() => {
    if (diagram?.mermaid && diagramRef.current && diagram.status === 'READY') {
      const renderDiagram = async () => {
        try {
          // Clear previous content
          diagramRef.current!.innerHTML = '';
          
          const { svg } = await mermaid.render(
            `mermaid-${analysisId}`,
            diagram.mermaid
          );
          
          if (diagramRef.current) {
            diagramRef.current.innerHTML = svg;
            
            // Add zoom/pan functionality
            addInteractivity(diagramRef.current);
          }
        } catch (err) {
          console.error('Mermaid render error:', err);
          setError('Failed to render diagram');
        }
      };

      renderDiagram();
    }
  }, [diagram, analysisId]);

  // Add zoom and pan
  const addInteractivity = (container: HTMLElement) => {
    const svg = container.querySelector('svg');
    if (!svg) return;

    let scale = 1;
    let panning = false;
    let pointX = 0;
    let pointY = 0;
    let start = { x: 0, y: 0 };

    svg.style.cursor = 'grab';

    // Zoom on scroll
    container.addEventListener('wheel', (e: WheelEvent) => {
      e.preventDefault();
      const delta = e.deltaY > 0 ? 0.9 : 1.1;
      scale *= delta;
      scale = Math.min(Math.max(0.5, scale), 3); // Limit zoom
      svg.style.transform = `translate(${pointX}px, ${pointY}px) scale(${scale})`;
    });

    // Pan on drag
    svg.addEventListener('mousedown', (e: MouseEvent) => {
      panning = true;
      start = { x: e.clientX - pointX, y: e.clientY - pointY };
      svg.style.cursor = 'grabbing';
    });

    document.addEventListener('mousemove', (e: MouseEvent) => {
      if (!panning) return;
      pointX = e.clientX - start.x;
      pointY = e.clientY - start.y;
      svg.style.transform = `translate(${pointX}px, ${pointY}px) scale(${scale})`;
    });

    document.addEventListener('mouseup', () => {
      panning = false;
      svg.style.cursor = 'grab';
    });
  };

  if (loading || diagram?.status === 'GENERATING') {
    return (
      <section className="diagram-panel">
        <h2 className="section-title">Call Graph Visualization</h2>
        <div className="diagram-loading">
          <div className="spinner" />
          <p>
            {diagram?.message || 'Generating call graph...'}
          </p>
          <p className="loading-subtext">
            This may take 30-60 seconds for large repositories
          </p>
        </div>
      </section>
    );
  }

  if (error || diagram?.status === 'FAILED') {
    return (
      <section className="diagram-panel">
        <h2 className="section-title">Call Graph Visualization</h2>
        <div className="diagram-error">
          <span className="error-icon">⚠️</span>
          <p>{error || diagram?.error || 'Diagram generation failed'}</p>
          {diagram?.verificationNotes && (
            <p className="error-details">{diagram.verificationNotes}</p>
          )}
        </div>
      </section>
    );
  }

  if (!diagram?.mermaid) {
    return (
      <section className="diagram-panel">
        <h2 className="section-title">Call Graph Visualization</h2>
        <div className="diagram-empty">
          <p>No call graph available for this PR.</p>
        </div>
      </section>
    );
  }

  return (
    <section className="diagram-panel">
      <div className="diagram-header">
        <h2 className="section-title">Call Graph Visualization</h2>
        
        {/* Metrics Summary */}
        {diagram.metrics && (
          <div className="metrics-summary">
            <div className="metric-chip">
              <span className="metric-label">Nodes:</span>
              <span className="metric-value">{diagram.metrics.totalNodes}</span>
            </div>
            <div className="metric-chip">
              <span className="metric-label">Edges:</span>
              <span className="metric-value">{diagram.metrics.totalEdges}</span>
            </div>
            <div className="metric-chip">
              <span className="metric-label">Avg Complexity:</span>
              <span className="metric-value">{diagram.metrics.avgComplexity.toFixed(1)}</span>
            </div>
          </div>
        )}
      </div>

      {/* Verification Notes */}
      {diagram.verificationNotes && (
        <div className="verification-notes">
          <span className="notes-icon">✅</span>
          <span className="notes-text">{diagram.verificationNotes}</span>
        </div>
      )}

      {/* Hotspots */}
      {diagram.metrics?.hotspots && diagram.metrics.hotspots.length > 0 && (
        <div className="hotspots-panel">
          <h3 className="hotspots-title">🔥 Modified Hotspots</h3>
          <div className="hotspots-list">
            {diagram.metrics.hotspots.map((hotspot, idx) => (
              <span key={idx} className="hotspot-tag">{hotspot}</span>
            ))}
          </div>
        </div>
      )}

      {/* Legend */}
      <div className="diagram-legend">
        <div className="legend-item">
          <span className="legend-box added" />
          <span>Added</span>
        </div>
        <div className="legend-item">
          <span className="legend-box removed" />
          <span>Removed</span>
        </div>
        <div className="legend-item">
          <span className="legend-box modified" />
          <span>Modified</span>
        </div>
        <div className="legend-item">
          <span className="legend-arrow">→</span>
          <span>Call</span>
        </div>
        <div className="legend-item">
          <span className="legend-text">💡 Scroll to zoom, drag to pan</span>
        </div>
      </div>

      {/* Mermaid Diagram */}
      <div className="diagram-container">
        <div ref={diagramRef} className="mermaid-diagram" />
      </div>
    </section>
  );
};

export default DiagramPanel;