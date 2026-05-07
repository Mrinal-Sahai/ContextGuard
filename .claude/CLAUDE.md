# CLAUDE.md — ContextGuard

Pull Request Intelligence Platform. Analyses GitHub PRs and returns risk scores, difficulty estimates, blast radius, a call graph diff, a Mermaid sequence diagram, and an AI-generated narrative — all grounded in peer-reviewed software engineering research.

---

## What This Project Does

ContextGuard answers three questions before merging a PR:

1. **What is the probability this PR introduces a production defect?** → Risk Score
2. **How cognitively demanding is this PR to review correctly?** → Difficulty Score
3. **If something goes wrong, how widely will the failure propagate?** → Blast Radius

It processes a PR in ~20–30 seconds and returns a structured JSON response consumed by a React frontend dashboard.

---

## Tech Stack

**Backend:** Spring Boot 3, Java 17+, Maven, PostgreSQL (via JPA), Lombok  
**Frontend:** React 18, TypeScript, Tailwind CSS, Vite, Mermaid.js  
**AST Parsing:** JavaParser + JavaSymbolSolver (Java), Tree-sitter via Node.js bridge (Python/Go/Ruby/JS/TS/Dart)  
**Tier 2 Type Resolution:** TypeScript Compiler API, Pyright, `go/types` binary, Dart Analysis Server (LSP)  
**AI Providers:** OpenAI, Google Gemini (default provider is `stub` — no LLM calls unless `LLM_PROVIDER=openai` or `LLM_PROVIDER=gemini` is set)  
**Infrastructure:** Docker (multi-stage), Docker Compose, PostgreSQL 15  
**Package:** `io.contextguard`

---

## Project Layout

```
src/main/java/io/contextguard/
├── service/
│   ├── PRAnalysisOrchestrator.java     ← main entry point, coordinates the full pipeline
│   ├── RiskScoringEngine.java          ← 5-signal weighted risk formula
│   ├── DiffMetadataAnalyzer.java       ← per-file risk classification (LOC, CC, critical path)
│   ├── AIGenerationService.java        ← AST-enriched prompt builder + LLM narrative
│   ├── BlastRadiusAnalyzer.java        ← module/layer/domain scope classification
│   └── criticalpath/
│       └── CriticalPathDetector.java   ← per-file criticality scoring (+5 for auth/payment, etc.)
│
├── analysis/flow/
│   ├── ASTParserService.java           ← two-pass multi-language AST parser (see details below)
│   ├── FlowExtractorService.java       ← call graph diff (base SHA vs head SHA)
│   ├── DiagramService.java             ← orchestrates call graph → diagram → narrative
│   ├── LLMSequenceDiagramService.java  ← LLM-powered Mermaid sequenceDiagram generation
│   ├── SequenceDiagramRenderer.java    ← algorithmic fallback diagram renderer
│   ├── GraphMetricsComputer.java       ← centrality, in/out degree computation
│   ├── CrossFileSymbolIndex.java       ← symbol resolution index (built in Pass 1)
│   ├── TreeSitterBridgeService.java    ← persistent Node.js process manager
│   ├── LanguageToolBridgeService.java  ← tsc/Pyright/go-types batch index manager
│   └── DartAnalysisBridgeService.java  ← Dart Analysis Server (LSP over stdio)
│
└── engine/
    ├── DifficultyScoringEngine.java    ← 5-signal weighted difficulty formula
    ├── ComplexityEstimator.java        ← hybrid McCabe + cognitive CC from diff lines
    └── DiffParser.java                 ← extracts added/deleted lines from unified diffs

tree-sitter-bridge/
├── tree-sitter-bridge.js              ← Node.js bridge: Python/Go/Ruby/JS/TS parsing
├── dart-parser.js                     ← Tree-sitter Dart grammar (fallback for Dart)
├── index-batch-handler.js             ← handles Pass 1 index_batch requests
├── go-types-bridge/
│   └── main.go                        ← Go binary using go/types for type-resolved edges
└── package.json

backend/src/main/resources/
└── application.yaml                ← Spring configuration (use .env for secrets)

frontend/src/
├── ReviewPage.tsx                  ← main dashboard page
├── RiskDifficultyPanel.tsx         ← self-explanatory risk + difficulty signals UI
├── ASTMetricsPanel.tsx             ← AST-derived metrics panel
├── MermaidDiagram.tsx              ← interactive pan/zoom diagram viewer
├── BreakdownChart.tsx              ← signal contribution bar charts
└── NarrativeSection.tsx            ← 6-section AI narrative display
```

---

## The Analysis Pipeline

Every request flows through this exact sequence in `PRAnalysisOrchestrator`:

```
POST /api/v1/pr-analysis
         │
         ▼
1. Parse PR URL → PRIdentifier (owner/repo/prNumber)
         │
         ▼
2. Cache check (validates headSha — stale if SHA changed)
         │
         ▼
3. GitHub API: fetchPRMetadata + fetchDiffFiles
         │
         ▼
4. DiffMetadataAnalyzer.analyzeDiff()
   → ComplexityEstimator (heuristic CC from diff lines)
   → CriticalPathDetector (per-file criticality score)
   → FileChangeSummary list with RiskLevel per file
         │
         ▼
5. RiskScoringEngine.assessRisk()           ← Round 1 (heuristic)
6. DifficultyScoringEngine.assessDifficulty() ← Round 1 (heuristic)
7. BlastRadiusAnalyzer.analyze()
         │
         ▼
8. CacheService.save() → PRAnalysisResult persisted
         │
         ▼
9. DiagramService.generateDiagram() [async]
   │
   ├── FlowExtractorService.generateDiagram()
   │     ├── ASTParserService.parseDirectoryFromGithub(baseSHA, files)
   │     │     ├── Pass 1: fetch all files → build CrossFileSymbolIndex
   │     │     └── Pass 2: parse with type resolution → FlowNode/FlowEdge
   │     ├── ASTParserService.parseDirectoryFromGithub(headSHA, files)
   │     ├── computeDifferential() → CallGraphDiff
   │     └── feedbackASTMetricsIntoDiffMetrics()
   │           → replaces heuristic CC delta with AST-accurate values
   │           → writes maxCallDepth, hotspotMethodIds, avgChangedMethodCC
   │
   ├── RiskScoringEngine.assessRisk()     ← Round 2 (AST-accurate)
   ├── DifficultyScoringEngine.assessDifficulty() ← Round 2 (AST-accurate)
   │
   ├── LLMSequenceDiagramService.generate()
   │     → LLM prompt with AST evidence → Mermaid sequenceDiagram
   │     → fallback: SequenceDiagramRenderer (algorithmic)
   │
   └── AIGenerationService.generateSummary()
         → enriched prompt with annotations, hotspots, call chain
         → 6-section structured JSON narrative
         │
         ▼
10. repository.save() → full result persisted
11. GET /api/v1/pr-analysis/{analysisId} → PRIntelligenceResponse
```

---

## Key Design Decisions

### Dual-Pipeline Accuracy

Every analysis runs **twice**:

- **Round 1** (immediate): `ComplexityEstimator` runs on raw diff lines — fast but noisy. Raw CC delta for an 18-file PR might read as 1296 due to keyword matches in strings/comments/annotations.
- **Round 2** (after AST): `FlowExtractorService` feeds accurate AST values back into `DiffMetrics`. `RiskScoringEngine` and `DifficultyScoringEngine` re-run with corrected data. The frontend always receives Round 2 values.

Round 1 exists purely as a latency hedge. It is never shown to users if Round 2 completes.

### Two-Pass AST Parsing

`ASTParserService` runs in two passes per analysis (once for base SHA, once for head SHA):

- **Pass 1 — Collect:** All files fetched in parallel. Every class/method/function/import/field type registered into `CrossFileSymbolIndex`. No call edges resolved yet.
- **Pass 2 — Resolve:** With the complete symbol universe available, all raw call edges resolved using a 5-step cascade: `self/this` → variable type lookup → import alias → uppercase class name → bare function.

This is what enables cross-file edge resolution — in Pass 1, `PaymentValidator` hasn't been parsed yet when `OrderService` is being processed. In Pass 2, it has.

### Deterministic Analysis IDs

`PRAnalysisOrchestrator.deriveAnalysisId()` computes a UUID from `SHA-256(owner/repo#prNumber@headSha)`. The same commit always produces the same `analysisId`. Different commits always produce different IDs. This was changed from `UUID.randomUUID()` to fix broken idempotency after server restarts.

### Node Modification Detection

A `FlowNode` is classified MODIFIED if **any** of these change between base and head:
1. Cyclomatic complexity
2. LOC by more than 10% (Mockus & Votta 2000 threshold)
3. Return type (breaking API change)
4. Annotations (e.g. `@Transactional` removed → DB operations no longer atomic)

### AST Metrics Fed Back

After `FlowExtractorService` completes, these are written into `DiffMetrics`, replacing heuristic estimates:
- `complexityDelta` — net CC across all added/modified/removed methods
- `maxCallDepth` — longest call chain via BFS on added edges
- `hotspotMethodIds` — top-5 methods by centrality
- `avgChangedMethodCC` — average CC of changed methods only
- `removedPublicMethods` / `addedPublicMethods` — API surface changes

---

## Scoring Formulas

### Risk Score

```
PR_Risk = 0.20 × avg_file_risk
        + 0.30 × peak_file_risk        ← highest weight: one bad file dominates
        + 0.20 × complexity_delta      ← normalized: delta / (20 + delta)
        + 0.20 × critical_path_density
        + 0.10 × test_coverage_gap
```

File risk numeric mapping: `LOW=0.15, MEDIUM=0.40, HIGH=0.70, CRITICAL=1.00`

Thresholds: `<0.25 LOW | <0.50 MEDIUM | <0.75 HIGH | ≥0.75 CRITICAL`

### Difficulty Score

```
Difficulty = 0.35 × cognitive_complexity   ← primary driver
           + 0.25 × code_size (linesAdded only, NOT total churn)
           + 0.20 × arch_context (0.55×layers + 0.45×domains)
           + 0.10 × file_spread
           + 0.10 × critical_file_concentration
```

All signals normalized via saturation function: `value / (pivot + value)`

Pivots: cognitive=15, LOC=400, files=7, context=3, critical=0.25

Levels: `<0.15 TRIVIAL | <0.35 EASY | <0.55 MODERATE | <0.75 HARD | ≥0.75 VERY_HARD`

### Cognitive Complexity (ComplexityEstimator)

Hybrid McCabe + nesting penalty. Each decision point scores `1 + currentNestingDepth`.

Counted: `if, else if, for, for-each, while, case, catch, &&, ||, ?:`  
Not counted: `else, switch, finally`

The `DifficultyScoringEngine` caps raw CC delta at 200 to prevent inflation from diff-line keyword counting (commented strings/annotations).

### File Risk Classification (DiffMetadataAnalyzer)

Each file scored on four dimensions summed into a total:
- Churn (additions+deletions): `<30→0, <80→1, <200→2, <400→3, ≥400→4`
- Complexity delta: `0→0, >0→1, ≥6→2, ≥12→3, ≥20→4`
- Critical path: `+3 if critical`
- Destructive change: `+2 if deleted/removed`

Total thresholds: `<3→LOW, <6→MEDIUM, <9→HIGH, ≥9→CRITICAL`

### Graph Centrality (GraphMetricsComputer)

```
centrality = (inDegree + outDegree) / max(1, 2 × (n - 1))
```

Using `2*(n-1)` as denominator (max possible degree in a directed graph) keeps scores in [0,1] and stable across graphs of different sizes. The old formula used `totalNodes` which approached 0 for large graphs.

---

## Multi-Language AST Support

All non-Java parsing goes through the persistent Node.js bridge (`TreeSitterBridgeService`). Languages with Tier 2 tool bridges go through `LanguageToolBridgeService` additionally.

| Language | Tool | CC Formula | Call Edges | Overall |
|---|---|---|---|---|
| Java | JavaParser + JavaSymbolSolver | McCabe exact | ~75% | ~9/10 |
| TypeScript | Tree-sitter + tsc Compiler API | eslint-compatible | ~85% | ~9/10 |
| Dart/Flutter | Tree-sitter + Dart Analysis Server (LSP) | dart_code_metrics | ~85% | ~9/10 |
| Go | Tree-sitter + `go/types` binary | gocyclo-compatible | ~80% | ~8.5/10 |
| Python | Tree-sitter + Pyright | radon-compatible | ~65% | ~7.5/10 |
| JavaScript | Tree-sitter only | eslint-compatible | index only | ~7/10 |
| Ruby | Tree-sitter only | rubocop-compatible | index only | ~6/10 |

The `CrossFileSymbolIndex` improves all languages by enabling cross-file call resolution even without a type inference tool.

**Known Java limitation:** `@Autowired` fields without a `new` constructor call anywhere in scope cannot be resolved by JavaSymbolSolver (Spring DI context is not available statically). These edges fall through to the `CrossFileSymbolIndex`.

**Known Go limitation:** Interface dispatch (`var v Validator = ...`) gives the interface method target, not the concrete implementation. Full resolution would require `go/ssa` call graph analysis with the full module graph.

---

## Bridge Architecture

```
Java (ASTParserService)
    │
    ├── TreeSitterBridgeService    ← persistent Node process, parse requests
    │       │
    │       └── tree-sitter-bridge.js
    │             ├── Python      (Tree-sitter + Pyright)
    │             ├── Go          (Tree-sitter + go-types-bridge binary)
    │             ├── Ruby        (Tree-sitter)
    │             ├── JS/TS       (Tree-sitter + TypeScript Compiler API)
    │             └── Dart        (Tree-sitter via dart-parser.js, fallback only)
    │
    ├── LanguageToolBridgeService  ← persistent Node process, index_batch requests
    │       │
    │       └── same tree-sitter-bridge.js (separate process)
    │             routes type=index_batch → index-batch-handler.js
    │             ├── TypeScript  (multi-file tsc Program for cross-file imports)
    │             ├── Python      (Pyright per-file)
    │             └── Go          (go-types-bridge per-file)
    │
    └── DartAnalysisBridgeService  ← dart language-server process (LSP)
            └── textDocument/definition per call site
```

**Bridge protocol:**
- `TreeSitterBridgeService` sends: `{"id","language","filePath","content"}` — no `type` field
- `LanguageToolBridgeService` sends: `{"id","type":"index_batch","language","files":{...}}` for Pass 1
  and `{"id","type":"parse","language","filePath","content"}` for Pass 2
- Bridge dispatches on `request.type`: `"index_batch"` → `index-batch-handler.js`; anything else → `parseFile()`

**Critical path notes (historical bugs, now fixed):**
- `tree-sitter-bridge.js` had a wrong `require()` path for `index-batch-handler` (`./tree-sitter-bridge/index-batch-handler` instead of `./index-batch-handler`) — caused the entire bridge process to crash at startup, making all non-Java parsing produce empty results.
- `ASTParserService.java` Pass 2 loop was creating `new JavaParser()` instead of passing `solverJavaParser` — JavaSymbolSolver (Tier A) never fired; all Java edges fell to Tier B/C heuristics.
- `dart-parser.js` existed but was not wired into the bridge dispatch — Dart tree-sitter fallback produced "Unsupported language: dart" errors.

All bridge services degrade gracefully — if Node/Go/Dart is unavailable, those files are skipped with a WARN log. Java parsing is never affected by non-Java failures.

---

## Sequence Diagram Generation

`LLMSequenceDiagramService` generates a Mermaid `sequenceDiagram` (not `graph TB`) showing **runtime execution flow** — what actually happens at runtime, not class hierarchy.

The LLM prompt is structured in 5 sections ordered by importance to diagram quality:
1. PR context (semantic intent — prevents generic labels)
2. AST evidence (concrete nodes/edges — prevents hallucination)
3. Complexity & hotspot signals (what to annotate with ⚠)
4. Size budget (MAX_PARTICIPANTS=10, MAX_ARROWS=25, MAX_ALT_BLOCKS=5)
5. Strict Mermaid syntax rules

Fallback chain: LLM → `SequenceDiagramRenderer` (algorithmic, deterministic) → minimal stub.

`validateAndTrim()` enforces all budget limits on LLM output before it reaches the client.

---

## AI Narrative Generation

`AIGenerationService` builds a structured prompt and calls the LLM to produce a 7-field JSON response:

```json
{
  "OVERVIEW": "...",
  "STRUCTURAL_IMPACT": "...",
  "BEHAVIORAL_CHANGES": "...",
  "RISK_INTERPRETATION": "...",
  "REVIEW_FOCUS": "...",
  "CHECKLIST": "...",
  "CONFIDENCE": "HIGH|MEDIUM|LOW"
}
```

**Anti-hallucination:** The prompt contains a `CHANGED_METHODS` section listing only `nodesAdded` and `nodesModified`. An `UNCHANGED_METHODS` negative examples section explicitly forbids the LLM from referencing those nodes. The constraint appears at both START and END of the prompt (Liu et al. 2023 "Lost in the Middle" — LLMs under-attend to middle content by ~40%).

**Post-AST rescoring:** `generateSummary()` calls `rescore()` and `rescoreDifficulty()` immediately before building the prompt, so the narrative describes AST-accurate scores, not Round 1 heuristic values.

**Annotation signals:** `@Transactional` removed → "DB operations no longer atomic", `@PreAuthorize` added → "Authorization gate added", `@Async` added → "Method now runs asynchronously". These are surfaced because they represent behavioral changes invisible in diff line counts.

---

## API Reference

### Submit PR for Analysis
```
POST /api/v1/pr-analysis
Header: X-GitHub-Token: ghp_...  (optional, for private repos)

{
  "prUrl": "https://github.com/owner/repo/pull/5",
  "aiProvider": "OPENAI" | "GEMINI",
  "githubToken": "ghp_...",
  "aiToken": "sk-..."
}

Response: { "success": true, "data": { "analysisId": "uuid", "cached": false } }
```

### Retrieve Analysis
```
GET /api/v1/pr-analysis/{analysisId}

Response: PRIntelligenceResponse (see below)
```

### Key Response Fields

| Field | Type | Description |
|---|---|---|
| `analysisId` | UUID | Deterministic — same commit always gives same ID |
| `metadata` | PRMetadata | title, author, branches, SHAs, prUrl |
| `metrics` | DiffMetrics | filesChanged, linesAdded, linesDeleted, complexityDelta, maxCallDepth, avgChangedMethodCC, hotspotMethodIds, removedPublicMethods, addedPublicMethods |
| `risk` | RiskAssessment | overallScore [0-1], level, breakdown (5 signals), reviewerGuidance |
| `difficulty` | DifficultyAssessment | overallScore [0-1], level, estimatedReviewMinutes, breakdown (5 signals) |
| `blastRadius` | BlastRadiusAssessment | scope (LOCALIZED/COMPONENT/CROSS_MODULE/SYSTEM_WIDE), affectedLayers, affectedDomains |
| `narrative` | AIGeneratedNarrative | 7 text sections + confidence + generatedAt |
| `mermaidDiagram` | string | Mermaid sequenceDiagram syntax, null if AST parse failed |
| `diagramMetrics` | DiagramMetrics | totalNodes, totalEdges, maxDepth, avgComplexity, hotspots[] |

---

## Docker & Infrastructure

**Two Dockerfiles exist — use the right one:**
- `Dockerfile` (project root) — **production / full build**. 5 stages, includes Flutter/Dart, Node.js bridge, go-types-bridge. This is what `docker compose up --build` uses.
- `backend/Dockerfile` — **legacy dev-only**. Builds only the Spring Boot jar. Non-Java parsing will silently fail. Use only for rapid Java-only iteration.

Multi-stage build with 5 stages:

1. **`flutter-builder`** — Downloads Flutter SDK (includes Dart + Analysis Server), pre-caches it
2. **`go-builder`** — Compiles `go-types-bridge` binary (`CGO_ENABLED=0`, fully static)
3. **`node-builder`** — `npm install` for tree-sitter grammars + TypeScript + Pyright + tree-sitter-dart
4. **`maven-builder`** — Builds Spring Boot fat jar
5. **Runtime** (`eclipse-temurin:17-jre-jammy`) — Assembles all artifacts, runs as non-root `contextguard` user

`JVM MaxRAMPercentage=60%` (not 70%) because the Dart Analysis Server consumes an additional ~300-600MB.

Memory limit in docker-compose: `3.5g` (raised from 2g for Dart support).

### Required Environment Variables
```properties
GITHUB_TOKEN                          # GitHub PAT (5000 req/hr vs 60 unauthenticated)
OPENAI
_API_KEY                     # or OpenAI key depending on provider
SPRING_DATASOURCE_URL                 # jdbc:postgresql://postgres:5432/contextguard
SPRING_DATASOURCE_USERNAME            # cguser
SPRING_DATASOURCE_PASSWORD            # cgpass
```

### Bridge Configuration (application.yaml)

These are now explicit entries in `application.yaml`. Docker overrides them via `docker-compose.yml` environment variables.

```yaml
# Local dev defaults (backend/ is user.dir when running mvn spring-boot:run)
treesitter.bridge.script-path=../tree-sitter-bridge/tree-sitter-bridge.js
treesitter.bridge.node-command=node
treesitter.bridge.timeout-ms=10000
treesitter.batch.timeout-ms=60000
GO_TYPES_BRIDGE_PATH=tree-sitter-bridge/go-types-bridge
dart.analysis.flutter-sdk-path=/opt/flutter
dart.analysis.timeout-ms=15000
dart.analysis.batch-timeout-ms=120000
dart.analysis.max-calls-per-file=200
```

### Start Everything
```bash
cp .env.example .env
# fill in GITHUB_TOKEN and OPENAI_API_KEY (or GEMINI_API_KEY) + set LLM_PROVIDER
docker compose up --build
```

---

## Frontend Components

| Component | Role |
|---|---|
| `ReviewPage.tsx` | Top-level page, fetches analysis by `analysisId`, owns dark/light mode state, PDF export |
| `RiskDifficultyPanel.tsx` | Self-explanatory risk + difficulty panel — each signal shows raw value, normalization formula, weight, and research evidence. Click to expand per signal. |
| `ASTMetricsPanel.tsx` | AST-derived metrics: complexityDelta, avgMethodCC, maxCallDepth, public API delta, hotspot list with centrality ranking |
| `MermaidDiagram.tsx` | Interactive viewer: pan/drag, scroll-wheel zoom, fit-to-screen, SVG download |
| `BreakdownChart.tsx` | Horizontal bar chart for risk or difficulty signal contributions, with per-signal tooltips |
| `NarrativeSection.tsx` | 6-section AI narrative. `NarrativeBlock.tsx` renders inline code from backtick/quote-wrapped identifiers |

---

## Research References

Every scoring weight and threshold in the codebase cites a paper. Key ones:

| Paper | Used For |
|---|---|
| McCabe (1976), IEEE TSE | CC formula: `CC = 1 + decision_points`. SonarQube refactoring threshold ≥10 |
| Banker et al. (1993), MIS Quarterly | `+1 CC ≈ +0.15 defects/KLOC` — justifies complexity weight 0.20 in risk |
| Mockus & Votta (2000), ICSM | Untested changes have 2× post-merge defect rate — test coverage gap weight 0.10 |
| Nagappan & Ball (2005), ICSE | Critical-path files have 3-4× baseline defect rate — critical path density weight 0.20 |
| Kim et al. (2008), IEEE TSE | 80% of bugs from 20% of files — peak file risk weight 0.30 |
| Bacchelli & Bird (2013), ICSE | Comprehension time dominates review cost — cognitive complexity weight 0.35 in difficulty |
| Rigby & Bird (2013), FSE | Optimal PR ≤400 LOC, ≤7 files — LOC pivot=400, file spread pivot=7 |
| Campbell (2018), SonarSource | Cognitive complexity predicts review mistakes better than flat McCabe |
| SmartBear (2011) | 200-400 LOC/hr optimal review speed; defect detection falls 40% after 60 min |
| Liu et al. (2023), arXiv | LLMs under-attend to middle-of-prompt by ~40% — drives primacy+recency prompt structure |
| Zimmermann et al. (2008), PROMISE | High-centrality nodes account for disproportionate defect propagation |

---

## Common Tasks for Claude

**Adding a new language to the AST parser:**
1. Add the `tree-sitter-<lang>` grammar to `tree-sitter-bridge/package.json`
2. Add a parse function in `tree-sitter-bridge.js` following the pattern of `parsePython` / `parseGoTree`
3. Add the file extension to `detectLanguageFromPath()` and `SUPPORTED_EXTENSIONS` in `ASTParserService`
4. Add the language to the `parseFilePass2()` dispatch switch
5. If a type inference tool exists, add a `indexBatch()` handler in `index-batch-handler.js` and register it in `LanguageToolBridgeService.languageAvailability`

**Changing a scoring weight:**
- All weights are constants at the top of `RiskScoringEngine` or `DifficultyScoringEngine`
- Both have a `static {}` block asserting weights sum to 1.0 — update it if you add/remove a signal
- Update the Javadoc table in the same class
- Update `CLAUDE.md` (this file)

**Adding a new signal to risk or difficulty:**
- Add the constant, compute the signal value in `assessRisk()`/`assessDifficulty()`
- Add a `SignalInterpretation` entry to the `signals` list
- Add the field to `RiskBreakdown`/`DifficultyBreakdown` DTO
- Add the `SIGNAL_META` entry in `BreakdownChart.tsx` for the frontend bar chart

**Changing what the AI narrative includes:**
- The prompt is built in `AIGenerationService.buildPrompt()`
- `CHANGED_METHODS` section controls what the LLM can reference — never remove the `UNCHANGED_METHODS` negative examples section
- Output schema is in `buildOutputSchema()` — change field names there and in `parseJsonResponse()`

**Changing the Mermaid diagram:**
- Prompt is in `LLMSequenceDiagramService.buildPrompt()`
- Budget constants `MAX_PARTICIPANTS`, `MAX_ARROWS`, `MAX_ALT_BLOCKS` are at the top of the class
- `validateAndTrim()` enforces limits on LLM output — do not bypass it
- `SequenceDiagramRenderer` is the fallback — it must always produce valid output

**Debugging non-deterministic node counts:**
Look for these patterns (all historically fixed, documented in `ASTParserService`):
- GitHub rate limit responses silently treated as source files (`isGitHubErrorResponse()`)
- Raw base64 GitHub /contents JSON returned undecoded (`decodeIfRawGitHubContentsJson()`)
- JavaParser log level was DEBUG → invisible failures (now WARN)
- `nodes.put()` for overloaded methods was last-writer-wins → now `putIfAbsent`

**Working with the Dart Analysis Server:**
- The server speaks LSP over stdio — `DartAnalysisBridgeService` owns the process lifecycle
- Files must be on disk (temp workspace) for type checking to work — in-memory `didOpen` alone is insufficient
- The `resolveByLocation()` method on `CrossFileSymbolIndex` maps LSP definition responses back to node IDs
- Dart lifecycle methods (`build`, `initState`, `setState`, etc.) are tracked specially for Flutter widget analysis

---

## Known Limitations

- **Java cross-file type resolution:** `@Autowired` fields (`private PaymentService service;` with no `new`) are invisible to JavaSymbolSolver without the Spring application context. These edges fall through to `CrossFileSymbolIndex` heuristics.
- **Go interface dispatch:** `var v Validator = ...` resolves to the interface method, not the concrete implementation. Full resolution requires `go/ssa` with the complete module graph.
- **Python type coverage:** Pyright's `--outputjson` mode gives diagnostics not a full symbol table. Type extraction is indirect and misses files with no type errors (because Pyright only reports what went wrong).
- **Ruby:** No production-grade static type tool. Call edge resolution is index-only (~40%).
- **Flutter size:** The Flutter SDK is ~1GB in the Docker image. This is unavoidable — the Dart Analysis Server ships as part of the SDK with no smaller subset available.
- **Diagram budget:** `MAX_PARTICIPANTS=10` is empirically calibrated for GitHub PR comment rendering. Mermaid fails silently at ~15 participants.