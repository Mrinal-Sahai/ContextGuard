# debug-backend — ContextGuard Backend Debugging Guide

Use this when the backend behaves unexpectedly. Work through the checklist below.

## 1. Non-deterministic node counts or flaky diagrams

Check these known causes (all historical, documented in `ASTParserService`):

```bash
# Look for GitHub rate limit responses being parsed as source files
grep -r "isGitHubErrorResponse" backend/src/main/java/
# Look for base64 decoding issues
grep -r "decodeIfRawGitHubContentsJson" backend/src/main/java/
```

- **Rate limit hit?** → Check GITHUB_TOKEN is set. Without token: 60 req/hr. With: 5000 req/hr.
- **Base64 not decoded?** → GitHub `/contents` API returns base64. `ASTParserService` must decode before parsing.
- **putIfAbsent vs put for overloaded methods?** → Last-writer-wins was a bug. Check `nodes.putIfAbsent()` usage.
- **JavaParser log level?** → Should be WARN not DEBUG to see parse failures.

## 2. Non-Java parsing silently failing

```bash
# Check tree-sitter bridge process started
docker logs contextguard-app 2>&1 | grep -i "tree.sitter\|bridge"

# Check bridge available flag
docker logs contextguard-app 2>&1 | grep "permanently unavailable\|bridge restarted"
```

- Bridge starts automatically at Spring startup via `@PostConstruct` in `TreeSitterBridgeService`
- Max 3 restart attempts — check logs for crash reason
- If `node` binary missing: container was built with `backend/Dockerfile` not root `Dockerfile`

## 3. Dart parsing failing

```bash
docker logs contextguard-app 2>&1 | grep -i "dart\|flutter\|LSP"
```

- Dart Analysis Server needs Flutter SDK at `/opt/flutter` — only present in root Dockerfile build
- LSP handshake: `initialize` → `initialized` → `textDocument/didOpen` per file → `textDocument/definition`
- Files must be on disk (temp workspace) — in-memory `didOpen` alone is insufficient

## 4. Round 2 scores not updating (still showing heuristic values)

```bash
docker logs contextguard-app 2>&1 | grep "feedbackASTMetrics\|Round 2\|rescored"
```

- `FlowExtractorService.feedbackASTMetricsIntoDiffMetrics()` must complete before Round 2 rescoring
- `DiagramService` runs async — check it's completing without exception
- Check `PRAnalysisOrchestrator` for async exception swallowing

## 5. LLM narrative missing or stale

```bash
docker logs contextguard-app 2>&1 | grep "AIGenerationService\|narrative\|OPENAI\|GEMINI"
```

- Provider = `stub` by default → no LLM calls → narrative will be null
- Set `LLM_PROVIDER=openai` and `OPENAI_API_KEY=...` in `.env`
- Check `AIGenerationService.rescore()` is called before `buildPrompt()` (post-AST rescoring)
- Anti-hallucination: prompt has `CHANGED_METHODS` and `UNCHANGED_METHODS` sections — never remove

## 6. Scoring weights seem wrong

```bash
grep -r "static {" backend/src/main/java/io/contextguard/service/RiskScoringEngine.java
grep -r "static {" backend/src/main/java/io/contextguard/engine/DifficultyScoringEngine.java
```

- Both classes have a `static {}` block asserting weights sum to 1.0
- If assertion fires at startup, a weight constant was changed without updating the sum

## 7. Docker build failures

```bash
# Full 5-stage build (production)
docker build -f Dockerfile . --progress=plain 2>&1 | tail -50

# Quick Java-only build (no bridges)
docker build -f backend/Dockerfile backend/ --progress=plain 2>&1 | tail -20
```

- Stage 1 (flutter-builder): ~5-10 min first time, cached after. Needs internet.
- Stage 2 (go-builder): needs `tree-sitter-bridge/go-types-bridge/` directory with `go.mod`
- Stage 3 (node-builder): `npm install` compiles native tree-sitter modules — needs build tools
