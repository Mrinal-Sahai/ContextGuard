# Dart/Flutter Support — Removal Reference

Dart/Flutter support was removed from ContextGuard to reduce image size and startup complexity. This document describes exactly what was removed and how to re-add it.

---

## What Was Removed

### Docker / Infra

| Item | Notes |
|---|---|
| `Dockerfile.dart-bridge` | Entire 120-line Dart bridge container (Flutter SDK + Node.js) |
| `docker-compose.yml` — `dart-bridge` service | Container definition, healthcheck, resource limits |
| `docker-compose.yml` — `BRIDGE_DART_URL` env var | In `contextguard` service |
| `docker-compose.yml` — `DART_ANALYSIS_*` env vars | `DART_ANALYSIS_TIMEOUT_MS`, `DART_ANALYSIS_BATCH_TIMEOUT_MS`, `DART_ANALYSIS_MAX_CALLS_PER_FILE` |
| `docker-compose.yml` — `dart-bridge` in `depends_on` | In `contextguard` service |
| `Dockerfile.tree-sitter` — `dart-parser.js` COPY | Line: `COPY tree-sitter-bridge/dart-parser.js ./dart-parser.js` |

### Node.js Bridge

| Item | Notes |
|---|---|
| `tree-sitter-bridge/dart-parser.js` | 386 lines — Tree-sitter Dart grammar fallback parser |
| `tree-sitter-bridge/dart-bridge-server.js` | 469 lines — Dart Analysis Server (LSP) HTTP wrapper on port 3001 |
| `tree-sitter-bridge/package.json` — `tree-sitter-dart` | npm dependency |
| `tree-sitter-bridge/tree-sitter-bridge.js` | Removed `require('./dart-parser')`, removed `dart` grammar entry, removed `dart: DART_GRAMMAR_AVAILABLE` capability, removed `case 'dart'` in `parseFile()` |

### Java Backend

| Item | Notes |
|---|---|
| `DartAnalysisBridgeService.java` | 324 lines — Spring service managing Dart Analysis Server LSP over HTTP |
| `ASTParserService.java` — `dartBridge` field + constructor param | `DartAnalysisBridgeService dartBridge` injected via constructor |
| `ASTParserService.java` — Dart in Pass 1 | `dartFiles` block with `dartBridge.indexBatch()` + fallback |
| `ASTParserService.java` — `case "dart"` in `parseFilePass2()` | Routed to `parseDartFile()` |
| `ASTParserService.java` — `parseDartFile()` method | Tried LSP → fallback to Tree-sitter |
| `ASTParserService.java` — `mapDartResultToGraph()` method | Mapped `DartNode`/`DartEdge` records to `FlowNode`/`FlowEdge` |
| `ASTParserService.java` — `.dart` extension detection | Removed from `detectLanguageFromPath()` and `SUPPORTED_EXTENSIONS` |
| `application.yaml` — `bridge.dart.url` | `${BRIDGE_DART_URL:http://localhost:3001}` |
| `application.yaml` — `dart.analysis.*` section | `timeout-ms`, `batch-timeout-ms`, `max-calls-per-file` |

---

## Re-implementation Guide

### Step 1 — Restore npm package

In `tree-sitter-bridge/package.json`:
```json
"tree-sitter-dart": "^1.0.0"
```

### Step 2 — Restore dart-parser.js

The file used `tree-sitter-dart` to parse `.dart` files at the Tree-sitter level. It exported:
- `parseDart(filePath, content)` → `{ nodes, edges }` (same schema as other language parsers)
- `DART_GRAMMAR_AVAILABLE` boolean

Re-create following the pattern of `parsePython` or `parseGoTree` in `tree-sitter-bridge.js`.

### Step 3 — Wire into tree-sitter-bridge.js

```javascript
const { parseDart, DART_GRAMMAR_AVAILABLE } = require('./dart-parser');

// In GRAMMARS object:
dart: DART_GRAMMAR_AVAILABLE ? loadGrammar('tree-sitter-dart') : null,

// In TIER2_CAPABILITIES:
dart: DART_GRAMMAR_AVAILABLE,

// In parseFile() switch:
case 'dart': {
    if (!DART_GRAMMAR_AVAILABLE) throw new Error('Dart grammar not available');
    return parseDart(filePath, content);
}
```

### Step 4 — Restore dart-bridge-server.js (Dart Analysis Server — LSP)

This was an HTTP server on port 3001 that:
1. Spawned `dart language-server --protocol=lsp` as a child process
2. Initialized the LSP handshake (`initialize` → `initialized`)
3. For each file: wrote it to a temp workspace, sent `textDocument/didOpen`, called `textDocument/definition` for each call site, parsed LSP responses into resolved edges
4. Exposed `POST /parse` and `POST /index-batch` matching the tree-sitter bridge API shape
5. Exposed `GET /health` returning `{ status, dartLsp: true }`

The Flutter SDK must be installed (includes `dart language-server`). Set `FLUTTER_SDK_PATH` env var.

### Step 5 — Restore Dockerfile.dart-bridge

Multi-stage Docker build:
1. Install Flutter SDK (specify exact version for reproducibility, e.g. `3.19.6`)
2. Run `flutter precache --no-ios --no-android --no-web` to warm Dart SDK cache
3. Copy `dart-bridge-server.js` and Node modules
4. `CMD ["node", "dart-bridge-server.js"]`
5. `EXPOSE 3001`
6. `HEALTHCHECK` on `http://localhost:3001/health`

Flutter SDK download URL (stable channel):
```
https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.x.x-stable.tar.xz
```

Image size will be ~1.1–1.2GB due to the Flutter SDK.

### Step 6 — Restore docker-compose.yml entries

Add `dart-bridge` service:
```yaml
dart-bridge:
  build:
    context: .
    dockerfile: Dockerfile.dart-bridge
  container_name: contextguard-dart-bridge
  networks:
    - contextguard-net
  healthcheck:
    test: ["CMD", "node", "-e",
           "require('http').get('http://localhost:3001/health', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))"]
    interval: 20s
    timeout: 10s
    start_period: 60s
    retries: 3
  restart: unless-stopped
  deploy:
    resources:
      limits:
        memory: 1.5g
        cpus: "1.0"
      reservations:
        memory: 512m
```

Add to `contextguard` service:
```yaml
environment:
  BRIDGE_DART_URL: http://dart-bridge:3001
  DART_ANALYSIS_TIMEOUT_MS: 15000
  DART_ANALYSIS_BATCH_TIMEOUT_MS: 120000
  DART_ANALYSIS_MAX_CALLS_PER_FILE: 200

depends_on:
  dart-bridge:
    condition: service_healthy
```

### Step 7 — Restore application.yaml entries

```yaml
bridge:
  dart:
    url: ${BRIDGE_DART_URL:http://localhost:3001}

dart:
  analysis:
    timeout-ms: 15000
    batch-timeout-ms: 120000
    max-calls-per-file: 200
```

### Step 8 — Restore DartAnalysisBridgeService.java

Spring `@Service` that called the dart-bridge HTTP server. Key API:
```java
boolean isAvailable()
boolean indexBatch(Map<String, String> files, CrossFileSymbolIndex symbolIndex)
DartParseResult parseFile(String filePath, String content, CrossFileSymbolIndex symbolIndex)

record DartParseResult(List<DartNode> nodes, List<DartEdge> edges) {}
record DartNode(String id, String label, String filePath, String classContext,
                int startLine, int endLine, String returnType,
                int cyclomaticComplexity, boolean isAsync) {}
record DartEdge(String from, String to, int sourceLine) {}
```

### Step 9 — Restore ASTParserService.java

Re-inject `DartAnalysisBridgeService dartBridge` into constructor, restore:
- Pass 1: `dartFiles` block with `dartBridge.indexBatch()` → fallback `indexViaBridgeFallback()`
- Pass 2: `case "dart" -> parseDartFile(...)`
- `parseDartFile()` method (tries LSP, falls back to Tree-sitter)
- `mapDartResultToGraph()` method
- `.dart` in `detectLanguageFromPath()` and `SUPPORTED_EXTENSIONS`

---

## Known Issues (as of removal)

- **Dart Analysis Server cold start** takes ~15s — the Docker `start_period: 60s` was intentional.
- **LSP temp workspace**: Files must be written to disk (not just `didOpen`) for type checking. The bridge managed a `tmp/dart-workspace/` directory.
- **Flutter lifecycle methods** (`build`, `initState`, `setState`, `dispose`) were tracked specially for widget analysis — the LSP resolved their overrides correctly.
- **Package imports** (`package:flutter/...`, `package:provider/...`) were not resolvable without a full `pub get` in the workspace — those edges fell through to the symbol index heuristics.
