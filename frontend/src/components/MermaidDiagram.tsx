import React, { useEffect, useRef, useState, useCallback } from "react";
import mermaid from "mermaid";
import {
  Network,
  ZoomIn,
  ZoomOut,
  Maximize2,
  Download,
  Eye,
  EyeOff,
  AlertTriangle,
  Copy,
  Check,
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
      // "strict" sanitizes SVG output and disables script injection.
      // "loose" was previously used but creates an XSS surface when LLM output
      // is passed directly to mermaid.render() without sanitization.
      securityLevel: "strict",
      flowchart: {
        useMaxWidth: false,
        htmlLabels: false, // must be false with securityLevel "strict"
        curve: "basis"
      }
    });
  }, [isDarkMode]);

  /* ---------------- Diagram Render ---------------- */

  /** Render with a hard timeout so a pathologically complex diagram can't freeze the UI. */
  const renderWithTimeout = (diagramText: string, timeoutMs = 12000): Promise<{ svg: string }> => {
    const renderPromise = mermaid.render(`m-${Date.now()}`, diagramText);
    const timeoutPromise = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error(`Mermaid render timed out after ${timeoutMs / 1000}s`)), timeoutMs)
    );
    return Promise.race([renderPromise, timeoutPromise]);
  };

  useEffect(() => {
    if (!diagram || !containerRef.current) return;

    const id = ++renderId.current;

    const render = async () => {
      try {
        const { svg } = await renderWithTimeout(diagram);

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
        // Extract the most useful part of the Mermaid error message.
        // Raw Mermaid errors often contain the full diagram text which is unhelpful.
        const raw: string = e?.message ?? "Unknown render error";
        const firstLine = raw.split("\n")[0].substring(0, 200);
        setError(firstLine);
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
          <DiagramError error={error} diagram={diagram} isDarkMode={isDarkMode} />
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

// ─── Diagram error panel ──────────────────────────────────────────────────────
// Shown when mermaid.render() throws. Displays:
//   - The parse error message (trimmed to the first meaningful line)
//   - The raw diagram source so devs can reproduce / paste into mermaid.live
//   - A copy button for the raw source

const DiagramError: React.FC<{ error: string; diagram: string; isDarkMode: boolean }> = ({
  error,
  diagram,
  isDarkMode,
}) => {
  const [copied, setCopied] = useState(false);
  const [showSource, setShowSource] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(diagram).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const border  = isDarkMode ? "border-rose-500/30"  : "border-rose-200";
  const bg      = isDarkMode ? "bg-rose-950/30"       : "bg-rose-50";
  const errText = isDarkMode ? "text-rose-300"         : "text-rose-700";
  const muted   = isDarkMode ? "text-slate-400"        : "text-slate-500";
  const codeBg  = isDarkMode ? "bg-slate-900 border-slate-700" : "bg-white border-slate-200";

  return (
    <div className={`m-6 rounded-xl border ${border} ${bg} p-5 space-y-4`}>
      {/* Header */}
      <div className="flex items-start gap-3">
        <AlertTriangle className="w-5 h-5 text-rose-400 shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <div className={`text-sm font-semibold ${errText}`}>Diagram render failed</div>
          <div className={`text-xs mt-1 font-mono break-all ${errText} opacity-80`}>{error}</div>
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-3 flex-wrap">
        <button
          onClick={() => setShowSource(s => !s)}
          className={`text-xs px-3 py-1.5 rounded-lg border ${isDarkMode ? "border-slate-600 text-slate-300 hover:bg-slate-700" : "border-slate-300 text-slate-600 hover:bg-slate-100"} transition-colors`}
        >
          {showSource ? "Hide" : "Show"} raw diagram source
        </button>
        <button
          onClick={handleCopy}
          className={`flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border transition-colors ${
            copied
              ? "border-emerald-500/40 text-emerald-400"
              : isDarkMode
              ? "border-slate-600 text-slate-300 hover:bg-slate-700"
              : "border-slate-300 text-slate-600 hover:bg-slate-100"
          }`}
        >
          {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
          {copied ? "Copied!" : "Copy source"}
        </button>
        <a
          href="https://mermaid.live"
          target="_blank"
          rel="noreferrer"
          className={`text-xs px-3 py-1.5 rounded-lg border ${isDarkMode ? "border-slate-600 text-slate-300 hover:bg-slate-700" : "border-slate-300 text-slate-600 hover:bg-slate-100"} transition-colors`}
        >
          Open mermaid.live ↗
        </a>
      </div>

      {/* Raw source */}
      {showSource && (
        <pre className={`text-xs font-mono rounded-lg border p-4 overflow-auto max-h-72 whitespace-pre-wrap break-all ${codeBg} ${muted}`}>
          {diagram}
        </pre>
      )}

      <div className={`text-xs ${muted}`}>
        Paste the source into{" "}
        <span className="font-mono">mermaid.live</span> to debug the syntax, then report the issue.
      </div>
    </div>
  );
};

export default MermaidDiagram;