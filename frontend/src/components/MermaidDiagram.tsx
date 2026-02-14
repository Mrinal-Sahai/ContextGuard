import React, { useEffect, useRef, useState, useCallback } from 'react';
import mermaid from 'mermaid';
import { Network, ZoomIn, ZoomOut, Maximize2, Download, Eye, EyeOff } from 'lucide-react';

interface MermaidDiagramProps {
  diagram: string;
  verificationNotes?: string;
  metrics?: {
    totalNodes: number;
    totalEdges: number;
    maxDepth: number;
    avgComplexity: number;
    hotspots: string[];
    callDistribution: Record<string, number>;
  };
  isDarkMode: boolean;
}

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({
  diagram,
  verificationNotes,
  metrics,
  isDarkMode
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewportRef = useRef<HTMLDivElement>(null);

  const [zoom, setZoom] = useState(1);
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const [showMetrics, setShowMetrics] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const dragStart = useRef<{ x: number; y: number } | null>(null);

  /* ------------------ Mermaid Init ------------------ */

  useEffect(() => {
    mermaid.initialize({
      startOnLoad: false,
      theme: isDarkMode ? 'dark' : 'default',
      securityLevel: 'loose',
      flowchart: {
        useMaxWidth: false,
        htmlLabels: true,
        curve: 'basis'
      }
    });
  }, [isDarkMode]);

  /* ------------------ Render Diagram ------------------ */

  useEffect(() => {
    if (!diagram || !containerRef.current) return;

    const render = async () => {
      try {
        containerRef.current!.innerHTML = '';
        const { svg } = await mermaid.render(`m-${Date.now()}`, diagram);
        containerRef.current!.innerHTML = svg;

        setError(null);
        setTimeout(fitToScreen, 50);
      } catch (e: any) {
        setError(e.message);
      }
    };

    render();
  }, [diagram]);

  /* ------------------ Fit to Screen ------------------ */

  const fitToScreen = useCallback(() => {
    const svg = containerRef.current?.querySelector('svg');
    const viewport = viewportRef.current;
    if (!svg || !viewport) return;

    const svgRect = svg.getBoundingClientRect();
    const viewRect = viewport.getBoundingClientRect();

    const scaleX = viewRect.width / svgRect.width;
    const scaleY = viewRect.height / svgRect.height;

    const newZoom = Math.min(scaleX, scaleY) * 0.95;

    const centerX = (viewRect.width - svgRect.width * newZoom) / 2;
    const centerY = (viewRect.height - svgRect.height * newZoom) / 2;

    setZoom(newZoom);
    setPosition({ x: centerX, y: centerY });
  }, []);

  /* ------------------ Zoom Controls ------------------ */

  const zoomBy = (factor: number) => {
    setZoom(prev => {
      const newZoom = prev * factor;
      return Math.min(Math.max(newZoom, 0.2), 10); // Now supports up to 1000%
    });
  };

  const handleWheel = (e: React.WheelEvent) => {
    e.preventDefault();
    const scaleAmount = e.deltaY > 0 ? 0.9 : 1.1;
    zoomBy(scaleAmount);
  };

  /* ------------------ Drag ------------------ */

  const handleMouseDown = (e: React.MouseEvent) => {
    setIsDragging(true);
    dragStart.current = {
      x: e.clientX - position.x,
      y: e.clientY - position.y
    };
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!isDragging || !dragStart.current) return;
    setPosition({
      x: e.clientX - dragStart.current.x,
      y: e.clientY - dragStart.current.y
    });
  };

  const handleMouseUp = () => {
    setIsDragging(false);
    dragStart.current = null;
  };

  /* ------------------ Download ------------------ */

  const handleDownload = () => {
    const svg = containerRef.current?.querySelector('svg');
    if (!svg) return;

    const blob = new Blob(
      [new XMLSerializer().serializeToString(svg)],
      { type: 'image/svg+xml' }
    );

    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'call-graph.svg';
    link.click();
    URL.revokeObjectURL(url);
  };

  /* ------------------ Styles ------------------ */

  const bg = isDarkMode ? 'bg-slate-900 border-slate-700' : 'bg-white border-slate-200';
  const text = isDarkMode ? 'text-white' : 'text-slate-900';
  const secondary = isDarkMode ? 'text-slate-400' : 'text-slate-600';
  const btn = isDarkMode
    ? 'bg-slate-800 hover:bg-slate-700 border-slate-600'
    : 'bg-slate-100 hover:bg-slate-200 border-slate-300';

  /* ------------------ UI ------------------ */

  return (
    <div className={`${bg} border rounded-xl overflow-hidden shadow-lg`}>
      {/* Header */}
      <div className="p-4 flex justify-between items-center border-b border-slate-700/40">
        <div className="flex items-center gap-3">
          <Network className="w-5 h-5 text-indigo-500" />
          <div>
            <h3 className={`font-semibold ${text}`}>Call Graph Visualization</h3>
            {verificationNotes && (
              <p className={`text-xs ${secondary}`}>{verificationNotes}</p>
            )}
          </div>
        </div>

        <div className="flex gap-2">
          <button onClick={() => setShowMetrics(!showMetrics)} className={`p-2 border rounded ${btn}`}>
            {showMetrics ? <EyeOff size={16} /> : <Eye size={16} />}
          </button>
          <button onClick={() => zoomBy(0.8)} className={`p-2 border rounded ${btn}`}>
            <ZoomOut size={16} />
          </button>
          <button onClick={() => zoomBy(1.25)} className={`p-2 border rounded ${btn}`}>
            <ZoomIn size={16} />
          </button>
          <button onClick={fitToScreen} className={`p-2 border rounded ${btn}`}>
            <Maximize2 size={16} />
          </button>
          <button onClick={handleDownload} className={`p-2 border rounded ${btn}`}>
            <Download size={16} />
          </button>
        </div>
      </div>

      {/* Metrics */}
      {showMetrics && metrics && (
        <div className="p-4 grid grid-cols-2 md:grid-cols-5 gap-4 text-sm border-b border-slate-700/30">
          <div><div className={secondary}>Nodes</div><div className={text}>{metrics.totalNodes}</div></div>
          <div><div className={secondary}>Edges</div><div className={text}>{metrics.totalEdges}</div></div>
          <div><div className={secondary}>Depth</div><div className={text}>{metrics.maxDepth}</div></div>
          <div><div className={secondary}>Complexity</div><div className={text}>{metrics.avgComplexity.toFixed(1)}</div></div>
          <div><div className={secondary}>Hotspots</div><div className={text}>{metrics.hotspots?.length ?? 0}</div></div>
        </div>
      )}

      {/* Diagram Viewport */}
      <div
        ref={viewportRef}
        className="relative h-[85vh] w-full overflow-hidden cursor-grab active:cursor-grabbing"
        onWheel={handleWheel}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={handleMouseUp}
        onMouseLeave={handleMouseUp}
      >
        {error ? (
          <div className="flex justify-center items-center h-full text-red-500">
            {error}
          </div>
        ) : (
          <div
            ref={containerRef}
            style={{
              transform: `translate(${position.x}px, ${position.y}px) scale(${zoom})`,
              transformOrigin: '0 0',
              transition: isDragging ? 'none' : 'transform 0.08s ease-out'
            }}
          />
        )}
      </div>
    </div>
  );
};

export default MermaidDiagram;