<div align="center">

# ContextGuard

**Pull Request Intelligence Platform**

Analyse any GitHub PR in ~20 seconds and get a risk score, difficulty estimate, blast-radius classification, AST-accurate call-graph diff, Mermaid sequence diagram, and an AI-generated review narrative — all grounded in peer-reviewed software engineering research.

[![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.x-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white)](https://docs.docker.com/compose/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## What ContextGuard Answers

Before merging a pull request, ContextGuard answers three questions:

| Question | Output |
|---|---|
| What is the probability this PR introduces a production defect? | **Risk Score** `[0–1]` + level |
| How cognitively demanding is this PR to review correctly? | **Difficulty Score** + estimated review minutes |
| If something goes wrong, how widely will the failure propagate? | **Blast Radius** `LOCALIZED → SYSTEM_WIDE` |

---

## Screenshot

> _Submit a PR URL → get a full intelligence report in ~20 seconds._

```
POST /api/v1/pr-analysis
{
  "prUrl": "https://github.com/owner/repo/pull/42",
  "aiProvider": "OPENAI",
  "aiToken": "sk-..."
}
```

The React dashboard visualises:
- Risk & difficulty panels with per-signal breakdowns and research citations
- Interactive pan/zoom Mermaid sequence diagram
- AST metrics (cyclomatic complexity delta, max call depth, hotspot methods)
- 6-section AI narrative (overview → checklist)

---

## Tech Stack

**Backend**
- Spring Boot 3 · Java 17+ · Maven · PostgreSQL 15 (JPA/Hibernate) · Lombok

**Frontend**
- React 18 · TypeScript · Tailwind CSS · Vite · Mermaid.js

**AST Parsing**
- Java → JavaParser + JavaSymbolSolver
- TypeScript/JavaScript → Tree-sitter + TypeScript Compiler API
- Python → Tree-sitter + Pyright
- Go → Tree-sitter + `go/types` binary (CGO-free, fully static)
- Dart/Flutter → Tree-sitter + Dart Analysis Server (LSP over stdio)
- Ruby → Tree-sitter (index-only)

**AI Providers**
- OpenAI · Google Gemini
- Default is `stub` — no LLM calls unless `LLM_PROVIDER=openai|gemini` is set

**Infrastructure**
- Docker (5-stage multi-arch build) · Docker Compose · PostgreSQL 15

---

## Architecture Overview

```
POST /api/v1/pr-analysis
        │
        ▼
PRAnalysisOrchestrator
        │
        ├── GitHub API ──────────────── PR metadata + unified diff
        │
        ├── DiffMetadataAnalyzer ─────── per-file risk classification
        │     ├── ComplexityEstimator    heuristic CC from diff lines (Round 1)
        │     └── CriticalPathDetector  +score for auth/payment/security paths
        │
        ├── RiskScoringEngine (Round 1)
        ├── DifficultyScoringEngine (Round 1)
        ├── BlastRadiusAnalyzer
        │
        └── DiagramService [async] ────── full AST pipeline
              │
              ├── ASTParserService ──── two-pass multi-language parsing
              │     Pass 1: build CrossFileSymbolIndex (all files in parallel)
              │     Pass 2: resolve call edges with full symbol universe
              │
              ├── FlowExtractorService ─ call-graph diff (base SHA vs head SHA)
              │
              ├── RiskScoringEngine (Round 2, AST-accurate)
              ├── DifficultyScoringEngine (Round 2, AST-accurate)
              │
              ├── LLMSequenceDiagramService ── Mermaid sequenceDiagram via LLM
              │     └── SequenceDiagramRenderer (algorithmic fallback)
              │
              └── AIGenerationService ─── 6-section structured narrative
```

The analysis runs **twice**: Round 1 uses heuristic CC from diff lines (immediate response), Round 2 replaces those values with AST-accurate measurements. Users always see Round 2.

---

## Scoring Formulas

### Risk Score

```
PR_Risk = 0.20 × avg_file_risk
        + 0.30 × peak_file_risk          ← one bad file dominates
        + 0.20 × complexity_delta        ← delta / (20 + delta)
        + 0.20 × critical_path_density
        + 0.10 × test_coverage_gap
```

Levels: `< 0.25 LOW` · `< 0.50 MEDIUM` · `< 0.75 HIGH` · `≥ 0.75 CRITICAL`

### Difficulty Score

```
Difficulty = 0.35 × cognitive_complexity   ← primary driver
           + 0.25 × code_size (linesAdded)
           + 0.20 × arch_context
           + 0.10 × file_spread
           + 0.10 × critical_file_concentration
```

Levels: `TRIVIAL` · `EASY` · `MODERATE` · `HARD` · `VERY_HARD`

All signals use a saturation normalisation: `value / (pivot + value)`.  
Pivots are calibrated to Rigby & Bird (2013) — optimal PRs ≤ 400 LOC, ≤ 7 files.

---

## Multi-Language Support

| Language | Tool | Call Edge Accuracy |
|---|---|---|
| Java | JavaParser + JavaSymbolSolver | ~75% |
| TypeScript | Tree-sitter + tsc Compiler API | ~85% |
| Dart/Flutter | Tree-sitter + Dart Analysis Server | ~85% |
| Go | Tree-sitter + `go/types` binary | ~80% |
| Python | Tree-sitter + Pyright | ~65% |
| JavaScript | Tree-sitter only | index-only |
| Ruby | Tree-sitter only | index-only |

---

## Quick Start

### Prerequisites

- Docker + Docker Compose
- A GitHub Personal Access Token (5 000 req/hr vs 60 unauthenticated)
- An OpenAI or Gemini API key (optional — stub mode works without one)

### Run

```bash
git clone https://github.com/Mrinal-Sahai/ContextGuard.git
cd ContextGuard

cp .env.example .env
# Edit .env — fill in GITHUB_TOKEN, OPENAI_API_KEY (or GEMINI_API_KEY), LLM_PROVIDER

docker compose up --build
```

| Service | URL |
|---|---|
| React dashboard | http://localhost:5173 |
| Spring Boot API | http://localhost:8080 |
| PostgreSQL | localhost:5432 |

### Environment Variables

```properties
GITHUB_TOKEN=ghp_...                   # Required
OPENAI_API_KEY=sk-...                  # Required if LLM_PROVIDER=openai
GEMINI_API_KEY=...                     # Required if LLM_PROVIDER=gemini
LLM_PROVIDER=stub                      # stub | openai | gemini
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/contextguard
SPRING_DATASOURCE_USERNAME=cguser
SPRING_DATASOURCE_PASSWORD=cgpass
```

---

## API Reference

### Submit a PR

```http
POST /api/v1/pr-analysis
Content-Type: application/json

{
  "prUrl": "https://github.com/owner/repo/pull/5",
  "aiProvider": "OPENAI",
  "githubToken": "ghp_...",
  "aiToken": "sk-..."
}
```

**Response:**
```json
{ "success": true, "data": { "analysisId": "uuid", "cached": false } }
```

### Retrieve Results

```http
GET /api/v1/pr-analysis/{analysisId}
```

Key response fields: `risk`, `difficulty`, `blastRadius`, `metrics`, `narrative`, `mermaidDiagram`, `diagramMetrics`.

Analysis IDs are **deterministic** — `SHA-256(owner/repo#prNumber@headSha)` — so the same commit always produces the same ID and is served from cache.

---

## Project Structure

```
ContextGuard/
├── backend/src/main/java/io/contextguard/
│   ├── service/
│   │   ├── PRAnalysisOrchestrator.java      ← pipeline entry point
│   │   ├── RiskScoringEngine.java
│   │   ├── DifficultyScoringEngine.java
│   │   ├── DiffMetadataAnalyzer.java
│   │   ├── AIGenerationService.java
│   │   └── BlastRadiusAnalyzer.java
│   ├── analysis/flow/
│   │   ├── ASTParserService.java            ← two-pass multi-language parser
│   │   ├── FlowExtractorService.java        ← call-graph diff
│   │   ├── CrossFileSymbolIndex.java
│   │   ├── TreeSitterBridgeService.java     ← persistent Node.js process
│   │   ├── LanguageToolBridgeService.java   ← tsc/Pyright/go-types
│   │   └── DartAnalysisBridgeService.java   ← Dart LSP
│   └── engine/
│       ├── DifficultyScoringEngine.java
│       ├── ComplexityEstimator.java
│       └── DiffParser.java
│
├── tree-sitter-bridge/
│   ├── tree-sitter-bridge.js               ← Node.js bridge (Python/Go/Ruby/JS/TS)
│   ├── dart-parser.js
│   ├── index-batch-handler.js
│   └── go-types-bridge/main.go             ← static Go binary
│
├── frontend/src/
│   ├── ReviewPage.tsx
│   ├── RiskDifficultyPanel.tsx
│   ├── ASTMetricsPanel.tsx
│   ├── MermaidDiagram.tsx
│   ├── BreakdownChart.tsx
│   └── NarrativeSection.tsx
│
├── Dockerfile                              ← production (5-stage)
├── docker-compose.yml
└── .env.example
```

---

## Research Foundations

Every scoring weight and threshold cites a peer-reviewed paper:

| Paper | Applied To |
|---|---|
| McCabe (1976) | CC formula — `CC = 1 + decision_points` |
| Banker et al. (1993) | `+1 CC ≈ +0.15 defects/KLOC` → complexity weight 0.20 |
| Mockus & Votta (2000) | Untested changes → 2× defect rate → coverage gap weight 0.10 |
| Nagappan & Ball (2005) | Critical-path files → 3-4× baseline defect rate → weight 0.20 |
| Kim et al. (2008) | 80% of bugs from 20% of files → peak file risk weight 0.30 |
| Bacchelli & Bird (2013) | Comprehension dominates review cost → cognitive CC weight 0.35 |
| Rigby & Bird (2013) | Optimal PR ≤ 400 LOC, ≤ 7 files → LOC pivot = 400, file pivot = 7 |
| Campbell (2018) | Cognitive complexity predicts review mistakes better than flat McCabe |
| SmartBear (2011) | Defect detection falls 40% after 60 min → review time estimates |
| Liu et al. (2023) | LLMs under-attend to prompt middles by ~40% → primacy+recency structure |
| Zimmermann et al. (2008) | High-centrality nodes → disproportionate defect propagation |

---

## Docker Details

The production `Dockerfile` uses five stages:

1. **`flutter-builder`** — Flutter SDK (includes Dart + Analysis Server)
2. **`go-builder`** — Compiles `go-types-bridge` (`CGO_ENABLED=0`, fully static)
3. **`node-builder`** — Tree-sitter grammars + TypeScript + Pyright
4. **`maven-builder`** — Spring Boot fat JAR
5. **Runtime** — `eclipse-temurin:17-jre-jammy`, non-root user, `MaxRAMPercentage=60%`

Memory limit: `3.5g` (raised from 2g to accommodate the Dart Analysis Server's ~300-600MB footprint).

> **Note:** `backend/Dockerfile` is a legacy dev-only build. Non-Java parsing silently fails when using it.

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit your changes following the existing commit style
4. Open a pull request — ContextGuard will analyse its own PR 

---

## License

MIT — see [LICENSE](LICENSE) for details.

---

<div align="center">

Built with Java, React, Tree-sitter, and a lot of peer-reviewed papers.

</div>
