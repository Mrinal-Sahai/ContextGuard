import React, { useEffect, useRef, useState, useCallback } from "react";
import mermaid from "mermaid";
import {
  Network,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Download,
  Eye,
  EyeOff
} from "lucide-react";

interface MermaidDiagramProps {
  diagram: string;
  verificationNotes?: string;
  metrics?: {
    totalNodes: number;
    totalEdges: number;
    maxDepth: number;
    avgComplexity: number;
    hotspots: string[];
  };
  isDarkMode: boolean;
}

const MIN_ZOOM = 0.2;
const MAX_ZOOM = 8;

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
  const [isDragging, setDragging] = useState(false);
  const [showMetrics, setShowMetrics] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const dragStart = useRef({ x: 0, y: 0 });
  const renderId = useRef(0);

  /* ---------------- Mermaid Init ---------------- */

  useEffect(() => {
    mermaid.initialize({
      startOnLoad: false,
      theme: isDarkMode ? "dark" : "default",
      securityLevel: "loose",
      flowchart: {
        useMaxWidth: false,
        htmlLabels: true,
        curve: "basis"
      }
    });
  }, [isDarkMode]);

  /* ---------------- Diagram Render ---------------- */

  useEffect(() => {
    if (!diagram || !containerRef.current) return;

    const id = ++renderId.current;

    const render = async () => {
      try {
        const { svg } = await mermaid.render(`m-${Date.now()}`, diagram);

        if (id !== renderId.current) return;

        containerRef.current!.innerHTML = svg;

        const svgEl = containerRef.current!.querySelector("svg");
        if (svgEl) {
          svgEl.style.width = "auto";
          svgEl.style.height = "auto";
        }

        setError(null);

        setTimeout(fitToScreen, 30);
      } catch (e: any) {
        setError(e.message);
      }
    };

    render();
  }, [diagram]);

  /* ---------------- Fit to Screen ---------------- */

  const fitToScreen = useCallback(() => {
    const svg = containerRef.current?.querySelector("svg");
    const viewport = viewportRef.current;

    if (!svg || !viewport) return;

    const svgWidth = svg.viewBox.baseVal.width || svg.getBBox().width;
    const svgHeight = svg.viewBox.baseVal.height || svg.getBBox().height;

    const vw = viewport.clientWidth;
    const vh = viewport.clientHeight;

    const scale = Math.min(vw / svgWidth, vh / svgHeight) * 0.9;

    setZoom(scale);

    setPosition({
      x: (vw - svgWidth * scale) / 2,
      y: (vh - svgHeight * scale) / 2
    });
  }, []);

  /* ---------------- Zoom ---------------- */

  const zoomBy = (factor: number, center?: { x: number; y: number }) => {
    setZoom(prev => {
      let next = prev * factor;
      next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, next));

      if (center) {
        const dx = center.x - position.x;
        const dy = center.y - position.y;

        const ratio = next / prev;

        setPosition({
          x: center.x - dx * ratio,
          y: center.y - dy * ratio
        });
      }

      return next;
    });
  };

  const handleWheel = (e: React.WheelEvent) => {
    e.preventDefault();

    const rect = viewportRef.current!.getBoundingClientRect();

    const center = {
      x: e.clientX - rect.left,
      y: e.clientY - rect.top
    };

    zoomBy(e.deltaY > 0 ? 0.9 : 1.1, center);
  };

  /* ---------------- Dragging ---------------- */

  const handleMouseDown = (e: React.MouseEvent) => {
    setDragging(true);
    dragStart.current = {
      x: e.clientX - position.x,
      y: e.clientY - position.y
    };
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (!isDragging) return;

    setPosition({
      x: e.clientX - dragStart.current.x,
      y: e.clientY - dragStart.current.y
    });
  };

  const stopDragging = () => setDragging(false);

  /* ---------------- Download ---------------- */

  const handleDownload = () => {
    const svg = containerRef.current?.querySelector("svg");
    if (!svg) return;

    const blob = new Blob(
      [new XMLSerializer().serializeToString(svg)],
      { type: "image/svg+xml" }
    );

    const url = URL.createObjectURL(blob);

    const a = document.createElement("a");
    a.href = url;
    a.download = "diagram.svg";
    a.click();

    URL.revokeObjectURL(url);
  };

  /* ---------------- Styles ---------------- */

  const bg = isDarkMode
    ? "bg-slate-900 border-slate-700"
    : "bg-white border-slate-200";

  const text = isDarkMode ? "text-white" : "text-slate-900";
  const secondary = isDarkMode ? "text-slate-400" : "text-slate-600";

  const btn = isDarkMode
    ? "bg-slate-800 hover:bg-slate-700 border-slate-600"
    : "bg-slate-500 hover:bg-slate-200 border-slate-300";

  /* ---------------- UI ---------------- */

  return (
    <div className={`${bg} border rounded-xl overflow-hidden shadow-lg`}>
      <div className="p-4 flex justify-between items-center border-b border-slate-700/40">
        <div className="flex items-center gap-3">
          <Network className="w-5 h-5 text-indigo-500" />
          <div>
            <h3 className={`font-semibold ${text}`}>Sequence Diagram</h3>
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

      {!showMetrics && metrics && (
        <div className="p-4 grid grid-cols-2 md:grid-cols-5 gap-4 text-sm border-b border-slate-700/30">
          <div>
            <div className={secondary}>Nodes</div>
            <div className={text}>{metrics.totalNodes}</div>
          </div>

          <div>
            <div className={secondary}>Edges</div>
            <div className={text}>{metrics.totalEdges}</div>
          </div>

          <div>
            <div className={secondary}>Depth</div>
            <div className={text}>{metrics.maxDepth}</div>
          </div>

          <div>
            <div className={secondary}>Complexity</div>
            <div className={text}>{metrics.avgComplexity.toFixed(1)}</div>
          </div>

          <div>
            <div className={secondary}>Hotspots</div>
            <div className={text}>{metrics.hotspots.length}</div>
          </div>
        </div>
      )}

      <div
        ref={viewportRef}
        className="relative h-[90vh] w-full overflow-hidden cursor-grab active:cursor-grabbing"
        onWheel={handleWheel}
        onMouseDown={handleMouseDown}
        onMouseMove={handleMouseMove}
        onMouseUp={stopDragging}
        onMouseLeave={stopDragging}
      >
        {error ? (
          <div className="flex items-center justify-center h-full text-red-500">
            {error}
          </div>
        ) : (
          <div
            ref={containerRef}
            style={{
              transform: `translate(${position.x}px, ${position.y}px) scale(${zoom})`,
              transformOrigin: "0 0",
              transition: isDragging ? "none" : "transform 0.08s ease-out"
            }}
          />
        )}
      </div>
    </div>
  );
};

export default MermaidDiagram;