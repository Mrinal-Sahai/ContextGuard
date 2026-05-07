import { useState, useEffect, useRef } from "react";

// ─── Color Palette: Midnight Executive + Teal accent ───────────────────────
// Primary: #0D1B2A (near-black navy), Secondary: #1B3A4B (deep teal-navy)
// Accent: #00D4FF (electric cyan), Warm: #FF6B35 (engineering orange)
// Text: #E8F4FD (near-white), Muted: #8BAFC9 (steel blue)

const SLIDES = [
  // ── SLIDE 1: Title ──────────────────────────────────────────────────────
  {
    id: 1,
    type: "title",
    title: "ContextGuard",
    subtitle: "Automated PR Intelligence: Risk, Difficulty & Cognitive Context at Review Time",
    meta: "by Mrinal Sahai · Spring 2026",
    tag: "Engineering Deep-Dive",
    notes: `ContextGuard started from a simple frustration: code reviews arrive hours or days after a PR is opened, and by then the author has lost the mental context of why the code was written. The system we've built goes far beyond context preservation—it now delivers a full static analysis intelligence layer that scores every PR on risk and review difficulty before a human even opens it. This talk covers the complete architecture, the scoring engines, and the self-explanatory dashboard that makes every number auditable.`,
    qa: [],
  },

  // ── SLIDE 2: The Problem ─────────────────────────────────────────────────
  {
    id: 2,
    type: "three-col",
    title: "The Problem",
    subtitle: "Three compounding failures in modern code review",
    cols: [
      {
        icon: "🧠",
        heading: "Context Loss",
        body: "Developers forget their own reasoning when reviews are delayed. Studies show 50–70% of comprehension time in code review is re-reading code the author already understood. ContextGuard eliminates that waste.",
        stat: "50–70%",
        statLabel: "review time is re-comprehension",
      },
      {
        icon: "⏳",
        heading: "Invisible Risk",
        body: "Reviewers have no quantified signal for how dangerous or complex a PR is before they start. Critical path changes and zero-test-coverage PRs arrive looking identical to trivial ones.",
        stat: "0",
        statLabel: "pre-review risk signals in vanilla GitHub",
      },
      {
        icon: "🔄",
        heading: "Opaque Scoring",
        body: "Tools that do produce risk scores give dimensionless numbers with no explanation. A \"42/100\" score tells a reviewer nothing actionable. What was measured? Why does it matter?",
        stat: "0 pts",
        statLabel: "actionable signal from unexplained scores",
      },
    ],
    notes: `The first problem — context loss — is the original motivation for the project. But as the system evolved we discovered a deeper issue: even if you surface the context, reviewers have no way to prioritise their review queue. The invisible-risk and opaque-scoring problems are what drove the architecture toward the full scoring-engine pipeline we'll describe.`,
  },

  // ── SLIDE 3: What ContextGuard Does Today ───────────────────────────────
  {
    id: 3,
    type: "two-col-feature",
    title: "What ContextGuard Does Today",
    subtitle: "Three engines, one unified PR intelligence layer",
    left: {
      heading: "Deployed Capabilities",
      items: [
        { icon: "🤖", text: "AI-generated PR summaries via Claude/GPT — what changed, why, and what risks exist" },
        { icon: "🎯", text: "Risk Score (0–100): weighted composite of peak file risk, avg file risk, cyclomatic complexity delta, critical-path density, and test coverage gap" },
        { icon: "🧩", text: "Difficulty Score (0–100): cognitive complexity delta, LOC, architectural layer/domain spread, file spread, and critical-impact count" },
        { icon: "📊", text: "Self-explanatory dashboard: every signal shows raw value, unit, plain-English meaning, research citation, and explicit formula math" },
        { icon: "🔗", text: "GitHub & Beanstalk webhook integration with signature validation and non-deterministic AST de-duplication" },
      ],
    },
    right: {
      heading: "Architectural Pillars",
      items: [
        { label: "Webhook Layer", detail: "Spring Boot + HMAC-256 validation" },
        { label: "AST Parser", detail: "JavaParser (per-call instances, thread-safe)" },
        { label: "Flow Extractor", detail: "CFG → cyclomatic Δ, call depth, fan-out, fan-in, nesting, exception paths" },
        { label: "Risk Engine", detail: "5-signal weighted composite, saturating normalization" },
        { label: "Difficulty Engine", detail: "5-signal weighted composite, architectural spread analysis" },
        { label: "AI Generation", detail: "Anthropic Claude / OpenAI GPT — summary + checklist" },
        { label: "Chrome Extension", detail: "Injects sidebar into GitHub PR UI" },
      ],
    },
    notes: `The system has evolved significantly from the original prototype. What started as 'AI summaries injected into GitHub' is now a full static-analysis pipeline with two independent scoring engines, a self-documenting dashboard, and a JavaParser-based AST analysis layer. Each of these will get a dedicated slide.`,
  },

  // ── SLIDE 4: End-to-End Architecture ────────────────────────────────────
  {
    id: 4,
    type: "pipeline",
    title: "End-to-End Architecture",
    subtitle: "From GitHub webhook to browser-rendered intelligence",
    stages: [
      {
        n: "1",
        label: "Webhook Intake",
        color: "#00D4FF",
        items: ["GitHub / Beanstalk PR events", "HMAC-SHA256 signature validation", "Event fan-out to pipeline"],
      },
      {
        n: "2",
        label: "Static Analysis",
        color: "#FF6B35",
        items: ["JavaParser AST (per-call, thread-safe)", "FlowExtractorService → 6 AST metrics", "CriticalPathDetector", "BlastRadiusAnalyzer"],
      },
      {
        n: "3",
        label: "Scoring Engines",
        color: "#00D4FF",
        items: ["RiskScoringEngine (5 signals × weights)", "DifficultyScoringEngine (5 signals × weights)", "SignalInterpretation DTOs emitted per signal"],
      },
      {
        n: "4",
        label: "AI Enrichment",
        color: "#FF6B35",
        items: ["AIGenerationService → Claude/GPT", "PR summary + why-changed", "Reviewer checklist + risk narrative"],
      },
      {
        n: "5",
        label: "Storage & Cache",
        color: "#00D4FF",
        items: ["PostgreSQL (analysis results)", "Redis (hot-path cache)", "Context freshness decay timer"],
      },
      {
        n: "6",
        label: "Frontend / Extension",
        color: "#FF6B35",
        items: ["Spring Boot REST API", "React ReviewPage + RiskDifficultyPanel", "Chrome Extension sidebar injection"],
      },
    ],
    notes: `The pipeline is fully event-driven and stateless at each stage — each service receives a structured payload and returns a typed result. The most interesting technical complexity lives in stages 2 and 3: the AST analysis and the scoring engines. A key architectural decision was to make scoring engines emit SignalInterpretation DTOs rather than raw numbers — this pushes interpretation logic to the backend where it belongs, and keeps the frontend purely presentational.`,
  },

  // ── SLIDE 5: AST & Flow Analysis ────────────────────────────────────────
  {
    id: 5,
    type: "two-col-technical",
    title: "AST & Control-Flow Analysis",
    subtitle: "JavaParser pipeline extracting 6 structural metrics per PR",
    left: {
      heading: "ASTParserService",
      code: `// Per-call JavaParser instantiation
// Thread-safety: no shared mutable state
JavaParser parser = new JavaParser();
ParseResult<CompilationUnit> result =
  parser.parse(sourceCode);

// Emits: MethodMetrics, ClassMetrics
// + DiffMetrics skeleton for flow layer`,
      bullets: [
        "Per-call parser instances — eliminates NONDET-3 race condition",
        "WARN-level logging for parse failures (non-fatal)",
        "Handles missing/unparseable files gracefully",
      ],
    },
    right: {
      heading: "FlowExtractorService — 6 Metrics",
      metrics: [
        { name: "Cyclomatic Complexity Δ", desc: "New branching decision paths added (if/for/while/catch/&&/||)" },
        { name: "Call Depth", desc: "Maximum method call chain depth in changed methods" },
        { name: "Fan-Out", desc: "Distinct external classes/services called" },
        { name: "Fan-In", desc: "How many callers reference changed methods" },
        { name: "Nesting Depth", desc: "Maximum block nesting level in changed code" },
        { name: "Exception Paths", desc: "try/catch/throw blocks added or modified" },
      ],
      note: "feedbackASTMetricsIntoDiffMetrics() fuses all 6 into DiffMetrics before scoring",
    },
    notes: `The AST layer was the source of several production bugs that are now fixed. The non-determinism bugs came from shared JavaParser state across threads. The fix was deceptively simple — create a new parser instance per call. The FlowExtractorService is where the interesting engineering lives: it runs a full control-flow graph walk on the diff, not just line counts, which is why our complexity metric is actually meaningful.`,
  },

  // ── SLIDE 6: Risk Scoring Engine ─────────────────────────────────────────
  {
    id: 6,
    type: "scoring",
    title: "Risk Scoring Engine",
    subtitle: "5-signal weighted composite with saturating normalization",
    formula: "Risk = 0.30×peak + 0.20×avg + 0.20×complexity + 0.20×criticalDensity + 0.10×testGap",
    signals: [
      { weight: 0.30, label: "Peak File Risk", unit: "0–1 file risk", norm: "direct (LOW=0.15, MED=0.40, HIGH=0.70, CRIT=1.00)", cite: "Kim et al. 2008, IEEE TSE" },
      { weight: 0.20, label: "Avg File Risk", unit: "mean across all files", norm: "direct", cite: "Forsgren et al. 2018, Accelerate" },
      { weight: 0.20, label: "Complexity Δ", unit: "CC units added", norm: "delta/(20+delta) — saturates at pivot=20", cite: "Banker et al. 1993, MIS Quarterly" },
      { weight: 0.20, label: "Critical Path Density", unit: "fraction of files on critical path", norm: "direct", cite: "Nagappan & Ball 2005, ICSE" },
      { weight: 0.10, label: "Test Coverage Gap", unit: "% prod files with no test changes", norm: "direct (1.0 = 100% uncovered)", cite: "Mockus & Votta 2000, ICSM" },
    ],
    thresholds: [
      { label: "LOW", range: "0–30", color: "#22C55E" },
      { label: "MEDIUM", range: "30–55", color: "#EAB308" },
      { label: "HIGH", range: "55–75", color: "#F97316" },
      { label: "CRITICAL", range: "75–100", color: "#EF4444" },
    ],
    notes: `Each weight was chosen based on empirical data from the cited papers. Peak risk gets the highest weight because a single CRITICAL file in a PR is the strongest predictor of post-merge defects. Test gap gets the lowest weight (0.10) — but its raw signal is evaluated independently per signal, so a 100% test gap still shows as CRITICAL on its own signal card even if the weighted contribution looks small. This dual visibility is a key design decision.`,
  },

  // ── SLIDE 7: Difficulty Scoring Engine ──────────────────────────────────
  {
    id: 7,
    type: "scoring",
    title: "Difficulty Scoring Engine",
    subtitle: "Quantifying reviewer cognitive load before the review starts",
    formula: "Difficulty = 0.35×cognitive + 0.25×size + 0.20×context + 0.10×spread + 0.10×critical",
    signals: [
      { weight: 0.35, label: "Cognitive Complexity Δ", unit: "branching paths added", norm: "delta/(50+delta) — pivot=50, split >50 recommended", cite: "Campbell 2018, SonarSource; Bacchelli & Bird 2013 ICSE" },
      { weight: 0.25, label: "PR Size (LOC)", unit: "lines changed", norm: "LOC/(400+LOC) — pivot=400, ~20min read time", cite: "Rigby & Bird 2013 FSE; SmartBear 2011" },
      { weight: 0.20, label: "Architectural Context", unit: "layers × domains crossed", norm: "combined spread of layer+domain count", cite: "Tamrawi et al. 2011 FSE; Bosu et al. 2015 MSR" },
      { weight: 0.10, label: "File Spread", unit: "prod + test file count", norm: "total files / (total + 10)", cite: "Rigby & Bird 2013 FSE" },
      { weight: 0.10, label: "Critical Impact", unit: "critical files touched", unit2: ">1 = block-merge signal", norm: "min(count, 3) / 3", cite: "Nagappan & Ball 2005 ICSE" },
    ],
    thresholds: [
      { label: "TRIVIAL", range: "0–20", color: "#22C55E" },
      { label: "EASY", range: "20–40", color: "#84CC16" },
      { label: "MODERATE", range: "40–60", color: "#EAB308" },
      { label: "HARD", range: "60–80", color: "#F97316" },
      { label: "VERY HARD", range: "80–100", color: "#EF4444" },
    ],
    notes: `Cognitive complexity delta is the dominant signal (0.35 weight) because branching logic is the single largest driver of reviewer cognitive load per the SonarSource and Bacchelli & Bird studies. The architectural context signal is novel — it measures how many architectural layers and business domains are crossed by a PR, which is a proxy for how much background knowledge a reviewer needs to have before they can effectively review the change.`,
  },

  // ── SLIDE 8: Self-Explanatory Dashboard ─────────────────────────────────
  {
    id: 8,
    type: "dashboard-showcase",
    title: "Self-Explanatory Scoring Dashboard",
    subtitle: "Every number has a raw value, plain-English meaning, research citation, and explicit formula",
    problem: {
      heading: "Before: Opaque weighted contributions",
      lines: [
        "Test Coverage Gap: +10.0%  ← looks small, ignorable",
        "Complexity Δ:      +11.1%  ← what does this mean?",
        "Peak Risk:         +12.0%  ← 3 steps from the real number",
      ],
      verdict: "A 100% test gap looks like the least important signal because its weight (0.10) buries it",
    },
    solution: {
      heading: "After: SignalInterpretation DTO per signal",
      card: {
        label: "🧪 TEST COVERAGE GAP",
        weight: "weight: 0.10",
        verdict: "CRITICAL",
        raw: "100% of production files uncovered",
        meaning: "3 production files changed, 0 test files modified. Untested changes have 2× post-merge bug rate.",
        cite: "Mockus & Votta, 2000 — ICSM",
        formula: "0.10 (weight) × 1.00 (signal) = +10 pts of 100",
      },
    },
    components: [
      "ScoreGauge — animated half-circle SVG, score 0–100",
      "ScoreTrack — horizontal bar with labelled threshold markers",
      "SignalCard — always expanded, raw value + meaning + citation + formula math",
      "FormulaBar — full equation with actual numbers: Score = 0.30×0.40 + 0.20×0.25 + ...",
      "Fallback builders — reconstruct from legacy raw* fields if backend not yet updated",
    ],
    notes: `The key insight is that signalVerdict is independent of the weighted contribution. Test gap can be CRITICAL at the signal level even when its weighted contribution is small — and the UI shows both facts simultaneously. This design prevents the perverse case where a catastrophic signal gets visually buried by its low formula weight. The FormulaBar shows the complete arithmetic with actual numbers so any engineer can verify the score by hand.`,
  },

  // ── SLIDE 9: Complete Data Flow ──────────────────────────────────────────
  {
    id: 9,
    type: "flow-detail",
    title: "Complete PR Analysis Data Flow",
    subtitle: "Tracing a single PR event from webhook to rendered intelligence",
    steps: [
      { n: "01", actor: "GitHub", action: "POST /webhook/github — PR opened event with HMAC-256 signature" },
      { n: "02", actor: "WebhookController", action: "Validates signature, extracts PR metadata, enqueues analysis job" },
      { n: "03", actor: "ASTParserService", action: "Fetches diff via GitHub API (githubToken flow — BUG-NONDET-1 fixed), parses changed .java files with per-call JavaParser" },
      { n: "04", actor: "FlowExtractorService", action: "Runs CFG walk → emits 6 AST metrics, calls feedbackASTMetricsIntoDiffMetrics() to fuse into DiffMetrics" },
      { n: "05", actor: "CriticalPathDetector", action: "Maps changed files against known critical-path registry, emits density ratio" },
      { n: "06", actor: "BlastRadiusAnalyzer", action: "Computes transitive call-graph impact of changed methods" },
      { n: "07", actor: "RiskScoringEngine", action: "Builds 5 SignalInterpretation DTOs, computes weighted score, classifies LOW/MEDIUM/HIGH/CRITICAL" },
      { n: "08", actor: "DifficultyScoringEngine", action: "Builds 5 SignalInterpretation DTOs, computes weighted score, classifies TRIVIAL→VERY HARD" },
      { n: "09", actor: "AIGenerationService", action: "Calls Claude/GPT with diff + metadata → returns PR summary, why-changed, reviewer checklist" },
      { n: "10", actor: "PostgreSQL + Redis", action: "Persists PRAnalysisResult, caches hot-path response for Chrome Extension" },
      { n: "11", actor: "ReviewPage.tsx", action: "Fetches analysis, renders RiskDifficultyPanel with full SignalInterpretation data" },
      { n: "12", actor: "Chrome Extension", action: "Injects sidebar into GitHub PR UI, displays scores + summary without leaving review page" },
    ],
    notes: `This is the complete happy path for a Java PR. Non-Java files skip the AST layer but still get risk-scored using file-level heuristics. The most interesting engineering challenge was ensuring idempotency — if a webhook fires twice for the same PR commit SHA, we detect and de-duplicate rather than double-scoring.`,
  },

  // ── SLIDE 10: Technology Stack ───────────────────────────────────────────
  {
    id: 10,
    type: "tech-stack",
    title: "Technology Stack",
    subtitle: "Production-grade choices with clear architectural rationale",
    groups: [
      {
        layer: "Backend",
        color: "#00D4FF",
        items: [
          { name: "Spring Boot 3", reason: "Webhook controllers, REST API, async analysis pipeline" },
          { name: "JavaParser", reason: "Thread-safe per-call AST construction from Java source diffs" },
          { name: "PostgreSQL", reason: "Durable storage for PR analysis results and context history" },
          { name: "Redis", reason: "Hot-path cache for Chrome Extension low-latency reads" },
        ],
      },
      {
        layer: "AI / NLP",
        color: "#FF6B35",
        items: [
          { name: "Anthropic Claude", reason: "Primary LLM — PR summaries, risk narrative, reviewer checklist" },
          { name: "OpenAI GPT", reason: "Fallback / A-B testing LLM path" },
          { name: "Custom heuristics", reason: "Token-efficient pre-filtering before LLM call to reduce cost" },
        ],
      },
      {
        layer: "Frontend",
        color: "#00D4FF",
        items: [
          { name: "React + TypeScript", reason: "ReviewPage, RiskDifficultyPanel, all UI components as .tsx files" },
          { name: "Lucide React", reason: "Icon library for signal cards and UI chrome" },
          { name: "Tailwind CSS", reason: "Utility-first styling, dark/light mode support" },
        ],
      },
      {
        layer: "Infrastructure",
        color: "#FF6B35",
        items: [
          { name: "Chrome Extension (MV3)", reason: "Sidebar injection into GitHub / Beanstalk PR UI" },
          { name: "GitHub Webhooks", reason: "PR event source with HMAC-256 signature validation" },
          { name: "Beanstalk Webhooks", reason: "Alternative SCM integration, same pipeline" },
        ],
      },
    ],
    notes: `The stack choices were driven by team familiarity and production-readiness requirements. JavaParser was specifically chosen over tree-sitter because we needed deep Java-specific semantics for the CFG walk. The per-call instantiation pattern was a late discovery — JavaParser's internal state is not thread-safe across concurrent parses, which caused the non-deterministic AST results bug.`,
  },

  // ── SLIDE 11: Key Engineering Challenges ────────────────────────────────
  {
    id: 11,
    type: "bugs-fixed",
    title: "Key Engineering Challenges Solved",
    subtitle: "Production bugs found, root-caused, and fixed",
    bugs: [
      {
        id: "BUG-NONDET-1",
        title: "GitHub Token Not Flowing to AST Fetch",
        symptom: "ASTParserService fetched diffs without auth — 401 on private repos",
        root: "Token extracted from event but not passed through to the GitHub API call chain",
        fix: "Threaded githubToken through all method signatures down to the HTTP client",
        impact: "Private repo AST analysis now works end-to-end",
      },
      {
        id: "BUG-NONDET-2",
        title: "GitHub API Error JSON Not Detected",
        symptom: "Error JSON responses from GitHub were parsed as source code, producing junk ASTs",
        root: "No content-type or error-field check before handing response to JavaParser",
        fix: "Added JSON error detection before parse attempt; logs WARN and skips gracefully",
        impact: "Eliminates silent garbage in AST metrics",
      },
      {
        id: "BUG-NONDET-3",
        title: "JavaParser Thread-Safety Race Condition",
        symptom: "Non-deterministic AST results on concurrent PR analysis jobs",
        root: "Shared JavaParser instance had mutable internal state",
        fix: "Per-call JavaParser instantiation — new instance per parse invocation",
        impact: "Fully deterministic, concurrent-safe AST analysis",
      },
      {
        id: "BUG-FLOW-1",
        title: "feedbackASTMetricsIntoDiffMetrics Missing",
        symptom: "6 AST metrics computed but never written into DiffMetrics — scoring engines saw zeros",
        root: "Method existed but was not called in the FlowExtractorService pipeline",
        fix: "Restored the call with all 6 metrics: cognitiveDelta, callDepth, fanOut, fanIn, nestingDepth, exceptionPaths",
        impact: "Difficulty scores now reflect actual code complexity",
      },
    ],
    notes: `These bugs are worth calling out explicitly because they illustrate the hidden complexity in integrating static analysis with external APIs. The thread-safety bug in particular is subtle — JavaParser looks stateless from the outside (you pass in source code, you get a tree), but internally it mutates symbol table state. The fix is two lines of code; finding it required methodically eliminating every other explanation.`,
  },

  // ── SLIDE 12: Speaker Notes, Q&A, Evaluation, Roadmap ──────────────────
  {
    id: 12,
    type: "evaluation",
    title: "System Evaluation",
    subtitle: "Strengths, limitations, and what comes next",
    strengths: [
      "Fully auditable scoring — every number has raw value + formula + research citation",
      "Dual-layer verdict: signal-level verdict independent of weighted contribution prevents burial of critical signals",
      "Thread-safe, deterministic AST analysis after NONDET fixes",
      "Non-blocking pipeline — AI generation is async, scoring is synchronous and fast",
      "Backward-compatible DTO evolution — fallback builders reconstruct from legacy raw* fields",
    ],
    limitations: [
      "Java-only AST analysis — TypeScript, Python, Go files fall back to heuristic scoring only",
      "Critical path registry is static — requires manual curation, no auto-discovery",
      "Risk weights are fixed constants — not calibrated per-team or per-repository",
      "No historical baseline — scores are absolute, not relative to this repo's normal PR",
      "Chrome Extension requires manual install — not available via Web Store yet",
    ],
    roadmap: [
      { phase: "Q2 2026", item: "Tree-sitter integration for TypeScript/Python/Go AST analysis" },
      { phase: "Q2 2026", item: "Repository-level weight calibration using historical PR defect data" },
      { phase: "Q3 2026", item: "Baseline scoring — show percentile vs. this repo's last 90 days" },
      { phase: "Q3 2026", item: "Auto-discovery for critical path files via change-failure-rate correlation" },
      { phase: "Q4 2026", item: "Chrome Extension Web Store publication + OAuth-based setup" },
      { phase: "2027", item: "ML-based weight learning from reviewer feedback and post-merge defect rates" },
    ],
    notes: `The biggest limitation today is the Java-only constraint on AST analysis. For polyglot repos this means the difficulty score misses complexity in non-Java files. The tree-sitter integration is on the Q2 roadmap specifically because tree-sitter provides a consistent API across 40+ languages, which would let us maintain one CFG extraction pipeline that works everywhere. The calibration roadmap item is potentially the highest-value improvement — right now a 'HIGH' score means the same thing for a greenfield prototype and a payment processing service, which is clearly wrong.`,
  },

  // ── SLIDE 13: Anticipated Q&A ────────────────────────────────────────────
  {
    id: 13,
    type: "qa",
    title: "Anticipated Questions",
    subtitle: "Prepared answers for engineering leadership review",
    questions: [
      {
        q: "How were the signal weights chosen? Are they validated?",
        a: "Weights are anchored to the empirical studies cited per signal (e.g., Kim et al. 2008 for file risk, Banker et al. 1993 for complexity). They are currently fixed constants. Validation against defect outcomes requires historical PR data, which is on the Q3 roadmap as repository-level calibration.",
      },
      {
        q: "Why does test gap have only 0.10 weight if it's so important?",
        a: "Its weighted contribution is intentionally modest because test presence correlates with, but doesn't predict, merge safety on its own. The design compensates by showing signal-level verdict independently — a 100% test gap shows CRITICAL even when its score contribution is small. Both facts are visible simultaneously.",
      },
      {
        q: "How does the system handle non-Java files?",
        a: "Non-Java files skip the AST/CFG layer and fall back to heuristic scoring: file-level risk lookup, line-count proxy for size, and directory-structure heuristics for layer detection. Tree-sitter integration is planned for Q2 2026 to close this gap.",
      },
      {
        q: "What happens if JavaParser fails on a file?",
        a: "ASTParserService logs WARN and skips the file — the analysis continues with available files rather than failing the whole PR. This was a deliberate reliability decision: partial analysis is better than no analysis.",
      },
      {
        q: "How is the critical path registry maintained?",
        a: "Currently static — manually curated list of file path patterns (e.g., payment/, auth/, database/). Auto-discovery via change-failure-rate correlation with production incidents is planned for Q3 2026.",
      },
      {
        q: "What is the latency from PR open to score available?",
        a: "Scoring (risk + difficulty) completes synchronously in the analysis pipeline — typically <500ms for a 10-file PR. AI generation is async and completes in 2–8 seconds depending on diff size. The Chrome Extension shows scores immediately and streams the AI summary as it arrives.",
      },
    ],
  },
];

// ─── Sub-components ─────────────────────────────────────────────────────────

function TitleSlide({ slide }) {
  return (
    <div style={{
      background: "linear-gradient(135deg, #0D1B2A 0%, #1B3A4B 60%, #0D1B2A 100%)",
      height: "100%", display: "flex", flexDirection: "column",
      justifyContent: "center", alignItems: "flex-start", padding: "60px 72px",
      position: "relative", overflow: "hidden",
    }}>
      {/* background grid */}
      <div style={{
        position: "absolute", inset: 0, opacity: 0.04,
        backgroundImage: "linear-gradient(#00D4FF 1px, transparent 1px), linear-gradient(90deg, #00D4FF 1px, transparent 1px)",
        backgroundSize: "48px 48px",
      }}/>
      {/* accent bar */}
      <div style={{ position: "absolute", left: 0, top: 0, bottom: 0, width: 6, background: "linear-gradient(180deg, #00D4FF, #FF6B35)" }}/>

      <div style={{ fontSize: 12, fontFamily: "'Consolas', monospace", color: "#00D4FF", letterSpacing: 4, textTransform: "uppercase", marginBottom: 24, background: "rgba(0,212,255,0.1)", padding: "6px 14px", border: "1px solid rgba(0,212,255,0.3)" }}>
        {slide.tag}
      </div>
      <h1 style={{ fontFamily: "'Georgia', serif", fontSize: 72, fontWeight: 700, color: "#E8F4FD", margin: 0, lineHeight: 1.0, letterSpacing: -2 }}>
        {slide.title}
      </h1>
      <div style={{ width: 80, height: 4, background: "linear-gradient(90deg,#00D4FF,#FF6B35)", margin: "24px 0" }}/>
      <p style={{ fontFamily: "'Calibri', sans-serif", fontSize: 22, color: "#8BAFC9", maxWidth: 680, lineHeight: 1.5, margin: "0 0 40px 0" }}>
        {slide.subtitle}
      </p>
      <div style={{ fontFamily: "'Consolas', monospace", fontSize: 13, color: "#8BAFC9" }}>{slide.meta}</div>

      {/* floating code snippet */}
      <div style={{
        position: "absolute", right: 72, top: "50%", transform: "translateY(-50%)",
        background: "rgba(0,0,0,0.5)", border: "1px solid rgba(0,212,255,0.2)",
        padding: "20px 28px", fontFamily: "Consolas, monospace", fontSize: 13,
        color: "#8BAFC9", lineHeight: 1.8, maxWidth: 320,
      }}>
        <div style={{ color: "#00D4FF", marginBottom: 8 }}>// PRAnalysisResult</div>
        <div><span style={{ color: "#FF6B35" }}>riskScore</span>: <span style={{ color: "#22C55E" }}>42</span> <span style={{ color: "#555" }}>// MEDIUM</span></div>
        <div><span style={{ color: "#FF6B35" }}>difficultyScore</span>: <span style={{ color: "#22C55E" }}>67</span> <span style={{ color: "#555" }}>// HARD</span></div>
        <div><span style={{ color: "#FF6B35" }}>signals</span>: <span style={{ color: "#00D4FF" }}>List&lt;SignalInterpretation&gt;</span></div>
        <div><span style={{ color: "#FF6B35" }}>summary</span>: <span style={{ color: "#EAB308" }}>"AI-generated"</span></div>
      </div>
    </div>
  );
}

function ThreeColSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "40px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 24, flex: 1, marginTop: 24 }}>
        {slide.cols.map((col, i) => (
          <div key={i} style={{
            flex: 1, background: "rgba(255,255,255,0.03)", border: "1px solid rgba(0,212,255,0.15)",
            padding: "28px 24px", display: "flex", flexDirection: "column",
          }}>
            <div style={{ fontSize: 32, marginBottom: 12 }}>{col.icon}</div>
            <div style={{ fontFamily: "'Georgia', serif", fontSize: 20, color: "#E8F4FD", fontWeight: 700, marginBottom: 14 }}>{col.heading}</div>
            <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 14, color: "#8BAFC9", lineHeight: 1.6, flex: 1 }}>{col.body}</div>
            <div style={{ marginTop: 20, borderTop: "1px solid rgba(0,212,255,0.1)", paddingTop: 16 }}>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 28, color: "#00D4FF", fontWeight: 700 }}>{col.stat}</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#8BAFC9", marginTop: 4 }}>{col.statLabel}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function TwoColFeatureSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "36px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 32, flex: 1, marginTop: 20 }}>
        <div style={{ flex: 1.1 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#00D4FF", letterSpacing: 3, textTransform: "uppercase", marginBottom: 16 }}>{slide.left.heading}</div>
          {slide.left.items.map((item, i) => (
            <div key={i} style={{ display: "flex", gap: 12, marginBottom: 14, alignItems: "flex-start" }}>
              <div style={{ fontSize: 16, flexShrink: 0, marginTop: 2 }}>{item.icon}</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 14, color: "#C8DCF0", lineHeight: 1.5 }}>{item.text}</div>
            </div>
          ))}
        </div>
        <div style={{ width: 1, background: "rgba(0,212,255,0.15)" }}/>
        <div style={{ flex: 0.9 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#FF6B35", letterSpacing: 3, textTransform: "uppercase", marginBottom: 16 }}>{slide.right.heading}</div>
          {slide.right.items.map((item, i) => (
            <div key={i} style={{
              display: "flex", justifyContent: "space-between", alignItems: "baseline",
              padding: "8px 0", borderBottom: "1px solid rgba(255,255,255,0.06)",
            }}>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#E8F4FD", fontWeight: 600 }}>{item.label}</div>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 12, color: "#8BAFC9", maxWidth: 180, textAlign: "right" }}>{item.detail}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function PipelineSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "36px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 0, flex: 1, marginTop: 24, alignItems: "center" }}>
        {slide.stages.map((stage, i) => (
          <div key={i} style={{ display: "flex", alignItems: "center", flex: 1 }}>
            <div style={{
              flex: 1, background: "rgba(255,255,255,0.03)", border: `1px solid ${stage.color}33`,
              padding: "16px 14px", height: "100%", maxHeight: 200,
            }}>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10 }}>
                <div style={{ width: 24, height: 24, borderRadius: "50%", background: stage.color, color: "#0D1B2A", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 11, fontWeight: 700, fontFamily: "Consolas, monospace", flexShrink: 0 }}>{stage.n}</div>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color: stage.color, fontWeight: 700, textTransform: "uppercase", letterSpacing: 1 }}>{stage.label}</div>
              </div>
              {stage.items.map((item, j) => (
                <div key={j} style={{ fontFamily: "'Calibri', sans-serif", fontSize: 11, color: "#8BAFC9", lineHeight: 1.5, marginBottom: 4 }}>• {item}</div>
              ))}
            </div>
            {i < slide.stages.length - 1 && (
              <div style={{ color: "#00D4FF", fontSize: 18, padding: "0 4px", flexShrink: 0 }}>→</div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function TwoColTechnicalSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "36px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 32, flex: 1, marginTop: 20 }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#FF6B35", letterSpacing: 3, textTransform: "uppercase", marginBottom: 14 }}>{slide.left.heading}</div>
          <div style={{
            background: "#050E18", border: "1px solid rgba(0,212,255,0.2)",
            padding: "16px 18px", fontFamily: "Consolas, monospace", fontSize: 12,
            color: "#8BAFC9", lineHeight: 1.7, marginBottom: 16, whiteSpace: "pre-wrap",
          }}>{slide.left.code}</div>
          {slide.left.bullets.map((b, i) => (
            <div key={i} style={{ display: "flex", gap: 10, marginBottom: 10, alignItems: "flex-start" }}>
              <div style={{ color: "#00D4FF", flexShrink: 0, marginTop: 2 }}>▸</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#C8DCF0", lineHeight: 1.5 }}>{b}</div>
            </div>
          ))}
        </div>
        <div style={{ width: 1, background: "rgba(0,212,255,0.15)" }}/>
        <div style={{ flex: 1 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#00D4FF", letterSpacing: 3, textTransform: "uppercase", marginBottom: 14 }}>{slide.right.heading}</div>
          {slide.right.metrics.map((m, i) => (
            <div key={i} style={{ marginBottom: 12, padding: "10px 14px", background: "rgba(255,255,255,0.03)", borderLeft: "3px solid rgba(0,212,255,0.4)" }}>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 12, color: "#00D4FF", fontWeight: 700 }}>{m.name}</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#8BAFC9", marginTop: 4 }}>{m.desc}</div>
            </div>
          ))}
          <div style={{ marginTop: 12, fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#FF6B35", fontStyle: "italic", borderTop: "1px solid rgba(255,107,53,0.2)", paddingTop: 12 }}>{slide.right.note}</div>
        </div>
      </div>
    </div>
  );
}

function ScoringSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "32px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ background: "rgba(0,212,255,0.05)", border: "1px solid rgba(0,212,255,0.2)", padding: "10px 18px", margin: "12px 0", fontFamily: "Consolas, monospace", fontSize: 12, color: "#00D4FF" }}>
        {slide.formula}
      </div>
      <div style={{ display: "flex", gap: 16, flex: 1 }}>
        <div style={{ flex: 2 }}>
          {slide.signals.map((sig, i) => (
            <div key={i} style={{ display: "flex", gap: 12, alignItems: "baseline", padding: "8px 0", borderBottom: "1px solid rgba(255,255,255,0.05)" }}>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 18, color: "#00D4FF", fontWeight: 700, width: 48, flexShrink: 0 }}>{(sig.weight * 100).toFixed(0)}%</div>
              <div style={{ flex: 1 }}>
                <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 14, color: "#E8F4FD", fontWeight: 600 }}>{sig.label} <span style={{ color: "#8BAFC9", fontSize: 11, fontStyle: "italic" }}>({sig.unit})</span></div>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#8BAFC9" }}>norm: {sig.norm}</div>
                <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 11, color: "#FF6B35" }}>📚 {sig.cite}</div>
              </div>
            </div>
          ))}
        </div>
        <div style={{ width: 1, background: "rgba(255,255,255,0.08)" }}/>
        <div style={{ width: 180 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color: "#8BAFC9", letterSpacing: 3, textTransform: "uppercase", marginBottom: 12 }}>Thresholds</div>
          {slide.thresholds.map((t, i) => (
            <div key={i} style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10 }}>
              <div style={{ width: 12, height: 12, borderRadius: 2, background: t.color, flexShrink: 0 }}/>
              <div>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 12, color: t.color, fontWeight: 700 }}>{t.label}</div>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#8BAFC9" }}>{t.range}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function DashboardShowcaseSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "32px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 28, flex: 1, marginTop: 16 }}>
        {/* Before */}
        <div style={{ flex: 1 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#EF4444", letterSpacing: 3, textTransform: "uppercase", marginBottom: 12 }}>✗ Before</div>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#8BAFC9", background: "#050E18", padding: "16px", border: "1px solid rgba(239,68,68,0.2)", marginBottom: 12 }}>
            {slide.problem.lines.map((l, i) => <div key={i} style={{ marginBottom: 6 }}>{l}</div>)}
          </div>
          <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#EF4444", fontStyle: "italic", lineHeight: 1.5 }}>{slide.problem.verdict}</div>
        </div>
        <div style={{ width: 1, background: "rgba(255,255,255,0.08)" }}/>
        {/* After */}
        <div style={{ flex: 1.3 }}>
          <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#22C55E", letterSpacing: 3, textTransform: "uppercase", marginBottom: 12 }}>✓ After — SignalInterpretation DTO</div>
          <div style={{ background: "rgba(255,255,255,0.03)", border: "1px solid rgba(0,212,255,0.25)", padding: "16px 18px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 13, color: "#E8F4FD", fontWeight: 700 }}>{slide.solution.card.label}</div>
              <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#8BAFC9" }}>{slide.solution.card.weight}</div>
                <div style={{ background: "#EF4444", color: "white", fontFamily: "Consolas, monospace", fontSize: 10, fontWeight: 700, padding: "2px 8px" }}>{slide.solution.card.verdict}</div>
              </div>
            </div>
            <div style={{ fontFamily: "Consolas, monospace", fontSize: 18, color: "#00D4FF", fontWeight: 700, marginBottom: 10 }}>{slide.solution.card.raw}</div>
            <div style={{ borderTop: "1px solid rgba(255,255,255,0.07)", paddingTop: 10, marginBottom: 10 }}>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#C8DCF0", lineHeight: 1.6 }}>{slide.solution.card.meaning}</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 11, color: "#FF6B35", marginTop: 6 }}>📚 {slide.solution.card.cite}</div>
            </div>
            <div style={{ background: "rgba(0,212,255,0.07)", padding: "8px 12px", fontFamily: "Consolas, monospace", fontSize: 12, color: "#00D4FF" }}>{slide.solution.card.formula}</div>
          </div>
          <div style={{ marginTop: 14 }}>
            {slide.components.map((c, i) => (
              <div key={i} style={{ fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#8BAFC9", padding: "4px 0", borderBottom: "1px solid rgba(255,255,255,0.04)" }}>▸ {c}</div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function FlowDetailSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "32px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ flex: 1, marginTop: 16, display: "grid", gridTemplateColumns: "1fr 1fr", gap: "8px 28px", alignContent: "start" }}>
        {slide.steps.map((step, i) => (
          <div key={i} style={{ display: "flex", gap: 12, alignItems: "flex-start", padding: "8px 0", borderBottom: "1px solid rgba(255,255,255,0.04)" }}>
            <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: i % 2 === 0 ? "#00D4FF" : "#FF6B35", fontWeight: 700, width: 28, flexShrink: 0 }}>{step.n}</div>
            <div>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#E8F4FD", fontWeight: 700, marginBottom: 3 }}>{step.actor}</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#8BAFC9", lineHeight: 1.4 }}>{step.action}</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function TechStackSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "36px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 20, flex: 1, marginTop: 20 }}>
        {slide.groups.map((group, i) => (
          <div key={i} style={{ flex: 1, background: "rgba(255,255,255,0.02)", border: `1px solid ${group.color}22`, padding: "18px 16px" }}>
            <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color: group.color, letterSpacing: 3, textTransform: "uppercase", marginBottom: 14, borderBottom: `1px solid ${group.color}33`, paddingBottom: 8 }}>{group.layer}</div>
            {group.items.map((item, j) => (
              <div key={j} style={{ marginBottom: 12 }}>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 12, color: "#E8F4FD", fontWeight: 700 }}>{item.name}</div>
                <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 11, color: "#8BAFC9", lineHeight: 1.4, marginTop: 3 }}>{item.reason}</div>
              </div>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

function BugsFixedSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "32px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16, flex: 1, marginTop: 16 }}>
        {slide.bugs.map((bug, i) => (
          <div key={i} style={{ background: "rgba(255,255,255,0.02)", border: "1px solid rgba(0,212,255,0.15)", padding: "16px 18px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 10 }}>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color: "#00D4FF", background: "rgba(0,212,255,0.1)", padding: "2px 8px" }}>{bug.id}</div>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color: "#22C55E" }}>FIXED</div>
            </div>
            <div style={{ fontFamily: "'Georgia', serif", fontSize: 14, color: "#E8F4FD", fontWeight: 700, marginBottom: 10 }}>{bug.title}</div>
            <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              {[["Symptom", bug.symptom, "#EF4444"], ["Root Cause", bug.root, "#EAB308"], ["Fix", bug.fix, "#22C55E"], ["Impact", bug.impact, "#00D4FF"]].map(([label, text, color]) => (
                <div key={label} style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
                  <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color, width: 64, flexShrink: 0, paddingTop: 2 }}>{label}</div>
                  <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#C8DCF0", lineHeight: 1.4 }}>{text}</div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function EvaluationSlide({ slide }) {
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "32px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ display: "flex", gap: 24, flex: 1, marginTop: 16 }}>
        <div style={{ flex: 1 }}>
          <SectionLabel label="✓ Strengths" color="#22C55E" />
          {slide.strengths.map((s, i) => (
            <div key={i} style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#C8DCF0", lineHeight: 1.5, marginBottom: 8, paddingLeft: 14, borderLeft: "2px solid #22C55E33" }}>{s}</div>
          ))}
          <SectionLabel label="✗ Current Limitations" color="#EF4444" style={{ marginTop: 20 }} />
          {slide.limitations.map((l, i) => (
            <div key={i} style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#C8DCF0", lineHeight: 1.5, marginBottom: 8, paddingLeft: 14, borderLeft: "2px solid #EF444433" }}>{l}</div>
          ))}
        </div>
        <div style={{ width: 1, background: "rgba(255,255,255,0.08)" }}/>
        <div style={{ flex: 1 }}>
          <SectionLabel label="→ Roadmap" color="#00D4FF" />
          {slide.roadmap.map((r, i) => (
            <div key={i} style={{ display: "flex", gap: 12, marginBottom: 12, alignItems: "flex-start" }}>
              <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: i < 2 ? "#FF6B35" : i < 4 ? "#EAB308" : "#8BAFC9", width: 64, flexShrink: 0, paddingTop: 2 }}>{r.phase}</div>
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#C8DCF0", lineHeight: 1.5 }}>{r.item}</div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function QASlide({ slide }) {
  const [open, setOpen] = useState(null);
  return (
    <div style={{ background: "#0D1B2A", height: "100%", display: "flex", flexDirection: "column", padding: "32px 56px" }}>
      <SlideHeader title={slide.title} subtitle={slide.subtitle} />
      <div style={{ flex: 1, marginTop: 16, overflowY: "auto" }}>
        {slide.questions.map((qa, i) => (
          <div key={i} style={{ marginBottom: 8, border: "1px solid rgba(0,212,255,0.15)", background: "rgba(255,255,255,0.02)" }}>
            <button
              onClick={() => setOpen(open === i ? null : i)}
              style={{
                width: "100%", textAlign: "left", background: "none", border: "none", cursor: "pointer",
                padding: "12px 16px", display: "flex", justifyContent: "space-between", alignItems: "center",
              }}
            >
              <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#E8F4FD", fontWeight: 600, flex: 1, paddingRight: 12 }}>Q: {qa.q}</div>
              <div style={{ color: "#00D4FF", fontSize: 16, flexShrink: 0 }}>{open === i ? "▲" : "▼"}</div>
            </button>
            {open === i && (
              <div style={{ padding: "0 16px 14px", fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#8BAFC9", lineHeight: 1.6, borderTop: "1px solid rgba(255,255,255,0.06)", paddingTop: 12 }}>
                <span style={{ color: "#FF6B35", fontWeight: 700 }}>A: </span>{qa.a}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function SlideHeader({ title, subtitle }) {
  return (
    <div style={{ marginBottom: 4 }}>
      <h2 style={{ fontFamily: "'Georgia', serif", fontSize: 28, color: "#E8F4FD", margin: 0, fontWeight: 700, letterSpacing: -0.5 }}>{title}</h2>
      {subtitle && <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 14, color: "#8BAFC9", marginTop: 4 }}>{subtitle}</div>}
    </div>
  );
}

function SectionLabel({ label, color, style = {} }) {
  return (
    <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color, letterSpacing: 2, textTransform: "uppercase", marginBottom: 10, ...style }}>{label}</div>
  );
}

// ─── Notes Panel ─────────────────────────────────────────────────────────────

function NotesPanel({ slide }) {
  return (
    <div style={{ background: "#050E18", borderTop: "1px solid rgba(0,212,255,0.15)", padding: "14px 56px", minHeight: 80 }}>
      <div style={{ fontFamily: "Consolas, monospace", fontSize: 10, color: "#00D4FF", letterSpacing: 3, textTransform: "uppercase", marginBottom: 8 }}>Speaker Notes</div>
      <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 13, color: "#8BAFC9", lineHeight: 1.6 }}>{slide.notes}</div>
    </div>
  );
}

// ─── Main App ─────────────────────────────────────────────────────────────────

export default function ContextGuardPresentation() {
  const [current, setCurrent] = useState(0);
  const [showNotes, setShowNotes] = useState(true);
  const [view, setView] = useState("deck"); // deck | outline

  const slide = SLIDES[current];

  function renderSlide(s) {
    switch (s.type) {
      case "title": return <TitleSlide slide={s} />;
      case "three-col": return <ThreeColSlide slide={s} />;
      case "two-col-feature": return <TwoColFeatureSlide slide={s} />;
      case "pipeline": return <PipelineSlide slide={s} />;
      case "two-col-technical": return <TwoColTechnicalSlide slide={s} />;
      case "scoring": return <ScoringSlide slide={s} />;
      case "dashboard-showcase": return <DashboardShowcaseSlide slide={s} />;
      case "flow-detail": return <FlowDetailSlide slide={s} />;
      case "tech-stack": return <TechStackSlide slide={s} />;
      case "bugs-fixed": return <BugsFixedSlide slide={s} />;
      case "evaluation": return <EvaluationSlide slide={s} />;
      case "qa": return <QASlide slide={s} />;
      default: return <div style={{ color: "white", padding: 40 }}>Unknown slide type: {s.type}</div>;
    }
  }

  return (
    <div style={{ background: "#030B14", minHeight: "100vh", display: "flex", flexDirection: "column", fontFamily: "system-ui" }}>
      {/* Top bar */}
      <div style={{ background: "#050E18", borderBottom: "1px solid rgba(0,212,255,0.15)", padding: "10px 24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div style={{ fontFamily: "Consolas, monospace", fontSize: 12, color: "#00D4FF", fontWeight: 700 }}>ContextGuard · PR Intelligence</div>
        <div style={{ display: "flex", gap: 8 }}>
          {["deck", "outline"].map(v => (
            <button key={v} onClick={() => setView(v)} style={{
              background: view === v ? "rgba(0,212,255,0.15)" : "none",
              border: "1px solid rgba(0,212,255,0.3)", color: view === v ? "#00D4FF" : "#8BAFC9",
              fontFamily: "Consolas, monospace", fontSize: 11, padding: "4px 12px", cursor: "pointer",
              textTransform: "uppercase", letterSpacing: 1,
            }}>{v}</button>
          ))}
          <button onClick={() => setShowNotes(!showNotes)} style={{
            background: showNotes ? "rgba(255,107,53,0.15)" : "none",
            border: "1px solid rgba(255,107,53,0.3)", color: showNotes ? "#FF6B35" : "#8BAFC9",
            fontFamily: "Consolas, monospace", fontSize: 11, padding: "4px 12px", cursor: "pointer",
            textTransform: "uppercase", letterSpacing: 1,
          }}>Notes</button>
        </div>
        <div style={{ fontFamily: "Consolas, monospace", fontSize: 12, color: "#8BAFC9" }}>
          {current + 1} / {SLIDES.length}
        </div>
      </div>

      {view === "outline" ? (
        // Outline view
        <div style={{ flex: 1, padding: "24px 56px", overflowY: "auto" }}>
          {SLIDES.map((s, i) => (
            <button key={i} onClick={() => { setCurrent(i); setView("deck"); }} style={{
              display: "block", width: "100%", textAlign: "left", background: "none", border: "none",
              cursor: "pointer", padding: "12px 16px", marginBottom: 4,
              background: i === current ? "rgba(0,212,255,0.08)" : "rgba(255,255,255,0.02)",
              border: `1px solid ${i === current ? "rgba(0,212,255,0.3)" : "rgba(255,255,255,0.06)"}`,
            }}>
              <div style={{ display: "flex", gap: 16, alignItems: "baseline" }}>
                <div style={{ fontFamily: "Consolas, monospace", fontSize: 11, color: "#00D4FF", width: 24 }}>{String(i + 1).padStart(2, "0")}</div>
                <div>
                  <div style={{ fontFamily: "'Georgia', serif", fontSize: 16, color: "#E8F4FD", fontWeight: 600 }}>{s.title}</div>
                  {s.subtitle && <div style={{ fontFamily: "'Calibri', sans-serif", fontSize: 12, color: "#8BAFC9", marginTop: 2 }}>{s.subtitle}</div>}
                </div>
              </div>
            </button>
          ))}
        </div>
      ) : (
        // Deck view
        <div style={{ flex: 1, display: "flex", flexDirection: "column" }}>
          {/* Slide content */}
          <div style={{ flex: 1, position: "relative" }}>
            <div style={{ position: "absolute", inset: 0, overflow: "hidden" }}>
              {renderSlide(slide)}
            </div>
          </div>

          {/* Speaker notes */}
          {showNotes && slide.notes && <NotesPanel slide={slide} />}

          {/* Navigation */}
          <div style={{ background: "#050E18", borderTop: "1px solid rgba(0,212,255,0.1)", padding: "10px 24px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <button onClick={() => setCurrent(Math.max(0, current - 1))} disabled={current === 0} style={{
              background: "none", border: "1px solid rgba(0,212,255,0.3)", color: current === 0 ? "#334" : "#00D4FF",
              fontFamily: "Consolas, monospace", fontSize: 12, padding: "6px 20px", cursor: current === 0 ? "default" : "pointer",
            }}>← PREV</button>

            {/* Slide dots */}
            <div style={{ display: "flex", gap: 6, flexWrap: "wrap", justifyContent: "center", maxWidth: "60%" }}>
              {SLIDES.map((_, i) => (
                <button key={i} onClick={() => setCurrent(i)} style={{
                  width: i === current ? 24 : 8, height: 8,
                  borderRadius: i === current ? 4 : "50%",
                  background: i === current ? "#00D4FF" : i < current ? "#1B3A4B" : "#0D1B2A",
                  border: `1px solid ${i === current ? "#00D4FF" : "rgba(0,212,255,0.2)"}`,
                  cursor: "pointer", transition: "all 0.2s", padding: 0,
                }}/>
              ))}
            </div>

            <button onClick={() => setCurrent(Math.min(SLIDES.length - 1, current + 1))} disabled={current === SLIDES.length - 1} style={{
              background: "none", border: "1px solid rgba(0,212,255,0.3)", color: current === SLIDES.length - 1 ? "#334" : "#00D4FF",
              fontFamily: "Consolas, monospace", fontSize: 12, padding: "6px 20px", cursor: current === SLIDES.length - 1 ? "default" : "pointer",
            }}>NEXT →</button>
          </div>
        </div>
      )}
    </div>
  );
}