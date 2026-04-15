#!/usr/bin/env node
/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TREE-SITTER BRIDGE — TIER 2
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * TIER 2 ADDITIONS vs TIER 1:
 *
 *  TypeScript (.ts):  TypeScript Compiler API (tsc) used instead of Tree-sitter.
 *                     Full type resolution — knows the declared type of every
 *                     variable, parameter, and return value.
 *                     Gives resolved call edges (not just "receiver.method" strings).
 *                     Falls back to Tree-sitter if tsc is unavailable.
 *
 *  Python (.py):      Pyright's --outputjson mode invoked per-file.
 *                     Provides resolved type info for calls across files.
 *                     Tree-sitter still used for node/CC extraction (faster).
 *                     Pyright results used to upgrade edge resolution quality.
 *                     Falls back to Tree-sitter-only if Pyright unavailable.
 *
 *  Go (.go):          GoTypesBridge binary (go/types + go/ssa) used for call edges.
 *                     Tree-sitter still used for node/CC extraction.
 *                     Falls back to Tree-sitter-only if go-types-bridge unavailable.
 *
 *  Ruby (.rb):        Unchanged from Tier 1 (no production-grade static type tool).
 *
 *  JavaScript (.js):  Unchanged from Tier 1 (TypeScript Compiler API handles .ts;
 *                     plain JS gets Tree-sitter + cross-file index in Java).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REQUEST PROTOCOL (unchanged):
 *   { "id": "...", "language": "...", "filePath": "...", "content": "..." }
 * RESPONSE:
 *   { "id": "...", "nodes": [...], "edges": [...], "error": null }
 * ─────────────────────────────────────────────────────────────────────────────
 */

'use strict';

const Parser   = require('tree-sitter');
const readline = require('readline');
const os       = require('os');
const path     = require('path');
const fs       = require('fs');
const { execSync, spawnSync } = require('child_process');
const { handleIndexBatch } = require('./index-batch-handler');

// ─── Grammar loading ──────────────────────────────────────────────────────────

function loadGrammar(packageName) {
  try { return require(packageName); }
  catch (e) {
    process.stderr.write(`[bridge] WARNING: Could not load grammar ${packageName}: ${e.message}\n`);
    return null;
  }
}

const GRAMMARS = {
  python:     loadGrammar('tree-sitter-python'),
  go:         loadGrammar('tree-sitter-go'),
  ruby:       loadGrammar('tree-sitter-ruby'),
  javascript: loadGrammar('tree-sitter-javascript'),
  typescript: null,
};
try {
  const tsLang = require('tree-sitter-typescript');
  GRAMMARS.typescript = tsLang.typescript;
} catch (e) {
  process.stderr.write(`[bridge] WARNING: TypeScript grammar unavailable: ${e.message}\n`);
}

// ─── Availability checks ──────────────────────────────────────────────────────

const TS_API_AVAILABLE = (() => {
  try { require('typescript'); return true; }
  catch (e) { process.stderr.write('[bridge] INFO: typescript package not found — using tree-sitter for .ts\n'); return false; }
})();

const PYRIGHT_AVAILABLE = (() => {
  try {
    const r = spawnSync('pyright', ['--version'], { encoding: 'utf8', timeout: 5000 });
    return r.status === 0;
  } catch (e) { process.stderr.write('[bridge] INFO: pyright not found — Python edges will use tree-sitter only\n'); return false; }
})();

const GO_BRIDGE_PATH = process.env.GO_TYPES_BRIDGE_PATH || path.join(__dirname, 'go-types-bridge');
const GO_BRIDGE_AVAILABLE = (() => {
  try {
    const r = spawnSync(GO_BRIDGE_PATH, ['--version'], { encoding: 'utf8', timeout: 3000 });
    return r.status === 0;
  } catch (e) { process.stderr.write('[bridge] INFO: go-types-bridge not found — Go edges will use tree-sitter only\n'); return false; }
})();

process.stderr.write(
  `[bridge] Tier 2 capabilities: tsc=${TS_API_AVAILABLE}, pyright=${PYRIGHT_AVAILABLE}, goTypes=${GO_BRIDGE_AVAILABLE}\n`
);

let parser = new Parser();

// ─── Exported capabilities (for HTTP server) ──────────────────────────────────

const TIER2_CAPABILITIES = {
  tsc:      TS_API_AVAILABLE,
  pyright:  PYRIGHT_AVAILABLE,
  goTypes:  GO_BRIDGE_AVAILABLE,
};

// ─── Entry point (only when run directly as a script) ─────────────────────────

if (require.main === module) {
const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

rl.on('line', async (line) => {   // ← add 'async'
  const trimmed = line.trim();
  if (!trimmed) return;
 
  let request;
  try {
    request = JSON.parse(trimmed);
  } catch (e) {
    writeResponse({ id: null, nodes: [], edges: [], error: `Invalid JSON: ${e.message}` });
    return;
  }
 
  const { id, type, language, filePath, content } = request;
 
  // ── NEW: handle index_batch requests (Pass 1 from LanguageToolBridgeService) ──
  if (type === 'index_batch') {
    try {
      const result = await handleIndexBatch(request);
      writeResponse({
        id,
        type: 'index_batch',
        symbols:       result.symbols       || [],
        importAliases: result.importAliases || [],
        variableTypes: result.variableTypes || [],
        error:         result.error         || null,
      });
    } catch (e) {
      writeResponse({
        id,
        type: 'index_batch',
        symbols: [], importAliases: [], variableTypes: [],
        error: e.message,
      });
    }
    return;
  }
 
  // ── EXISTING: handle parse requests (Pass 2 / Tree-sitter) ────────────────
  try {
    const result = parseFile(language, filePath, content);
    writeResponse({ id, nodes: result.nodes, edges: result.edges, error: null });
  } catch (e) {
    writeResponse({ id, nodes: [], edges: [], error: e.message });
  }
});

rl.on('close', () => process.exit(0));
function writeResponse(obj) { process.stdout.write(JSON.stringify(obj) + '\n'); }
} // end: if (require.main === module)

// ─── Dispatch ─────────────────────────────────────────────────────────────────

function parseFile(language, filePath, content) {
  switch (language) {
    case 'typescript': return parseTypeScript(filePath, content);
    case 'python':     return parsePython(filePath, content);
    case 'go':         return parseGo(filePath, content);
    case 'ruby':       return parseRuby(filePath, content);
    case 'javascript': return parseJavaScript(filePath, content);
    default:           throw new Error(`Unsupported language: ${language}`);
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TYPESCRIPT — TypeScript Compiler API (primary) + Tree-sitter (fallback/CC)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Strategy:
//   1. Tree-sitter used for: node discovery, CC, class context, decorators.
//      (Tree-sitter is faster and more reliable for structural extraction.)
//   2. TypeScript Compiler API used for: call edge resolution with full types.
//      The compiler creates a virtual program from the file content, then
//      the type checker resolves every CallExpression to its declared symbol.
//
// Why two tools?
//   - tsc is the authoritative type resolver but slow for large files
//   - Tree-sitter is fast and accurate for structural parsing
//   - Using both gives us the best of both worlds

function parseTypeScript(filePath, content) {
  // Always extract nodes + CC from Tree-sitter (fast, accurate)
  const tsResult = parseWithTreeSitter('typescript', filePath, content);

  if (!TS_API_AVAILABLE) {
    return tsResult;
  }

  // Augment with TypeScript Compiler API for type-resolved call edges
  try {
    const resolvedEdges = extractTypeScriptEdgesViaCompilerAPI(filePath, content, tsResult.nodes);
    return {
      nodes: tsResult.nodes,
      // Merge: use resolved edges where available, fall back to tree-sitter edges
      edges: mergeEdges(resolvedEdges, tsResult.edges),
    };
  } catch (e) {
    process.stderr.write(`[bridge] tsc error for ${filePath}: ${e.message}\n`);
    return tsResult;  // fall back to Tree-sitter-only result
  }
}

function extractTypeScriptEdgesViaCompilerAPI(filePath, content, parsedNodes) {
  const ts = require('typescript');

  // Create an in-memory compiler host
  // The compiler host lets us pass source content without touching the filesystem
  const sourceFile = ts.createSourceFile(filePath, content, ts.ScriptTarget.Latest, true);

  const defaultCompilerHost = ts.createCompilerHost({});
  const customHost = {
    ...defaultCompilerHost,
    getSourceFile: (name, languageVersion) => {
      if (name === filePath) return sourceFile;
      return defaultCompilerHost.getSourceFile(name, languageVersion);
    },
    fileExists:     (name) => name === filePath || defaultCompilerHost.fileExists(name),
    readFile:       (name) => name === filePath ? content : defaultCompilerHost.readFile(name),
    getDefaultLibFileName: (opts) => defaultCompilerHost.getDefaultLibFileName(opts),
    getCurrentDirectory:   ()     => defaultCompilerHost.getCurrentDirectory(),
    getDirectories:        (p)    => defaultCompilerHost.getDirectories(p),
    useCaseSensitiveFileNames:    () => true,
    getCanonicalFileName:         (f) => f,
    getNewLine:                   ()  => '\n',
    writeFile:                    ()  => {},
  };

  const program = ts.createProgram([filePath], {
    noEmit:           true,
    skipLibCheck:     true,
    strict:           false,
    allowJs:          true,
    target:           ts.ScriptTarget.Latest,
    moduleResolution: ts.ModuleResolutionKind.NodeJs,
  }, customHost);

  const checker = program.getTypeChecker();
  const edges   = [];

  // Build a set of node IDs defined in this file for self-reference checks
  const localNodeIds = new Set(parsedNodes.map(n => n.id));

  // Walk the AST looking for call expressions
  function walkForCalls(node, enclosingNodeId) {
    // Track which function we're inside
    let currentNodeId = enclosingNodeId;

    if (ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node) ||
        ts.isArrowFunction(node) || ts.isFunctionExpression(node)) {
      // Find matching node ID from our parsedNodes
      const nameNode = node.name;
      if (nameNode) {
        const matched = parsedNodes.find(n => n.label === nameNode.getText());
        if (matched) currentNodeId = matched.id;
      }
    }

    if (ts.isCallExpression(node) && currentNodeId) {
      const callExpr = node;
      const line     = sourceFile.getLineAndCharacterOfPosition(callExpr.getStart()).line + 1;

      try {
        const symbol = checker.getSymbolAtLocation(callExpr.expression);
        if (symbol) {
          // Try to get the fully qualified name — this is the resolved type-aware target
          const qualifiedName = checker.getFullyQualifiedName(symbol);
          if (qualifiedName && qualifiedName !== 'unknown') {
            // Map qualified name to node ID format: last two segments = ClassName.method
            // e.g. '"src/payments/service".PaymentService.process' → 'PaymentService.process'
            const parts     = qualifiedName.replace(/^"[^"]*"\./,'').split('.');
            const targetKey = parts.slice(-2).join('.');

            edges.push({
              from:       currentNodeId,
              to:         targetKey,    // Pass 2 in Java will resolve this against the index
              callType:   'METHOD_CALL',
              sourceLine: line,
              resolved:   true,         // flag: this came from tsc, high confidence
            });
          }
        }
      } catch (e) {
        // Type resolution failed for this call — emit unresolved edge
        const callText = callExpr.expression.getText();
        edges.push({
          from:       currentNodeId,
          to:         callText,
          callType:   'METHOD_CALL',
          sourceLine: line,
          resolved:   false,
        });
      }
    }

    ts.forEachChild(node, (child) => walkForCalls(child, currentNodeId));
  }

  walkForCalls(sourceFile, null);
  return edges;
}

function mergeEdges(resolvedEdges, fallbackEdges) {
  // Prefer resolved edges. If resolvedEdges is empty, use fallback.
  if (resolvedEdges && resolvedEdges.length > 0) return resolvedEdges;
  return fallbackEdges;
}

// ═══════════════════════════════════════════════════════════════════════════════
// PYTHON — Tree-sitter (nodes + CC) + Pyright (type-resolved edges)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Strategy:
//   1. Tree-sitter for all node/CC/class-context extraction (same as Tier 1).
//   2. Pyright --outputjson for type-resolved call targets.
//      Pyright writes a JSON diagnostics + symbol output that includes
//      type information for every identifier. We extract call targets from it.
//   3. Merge: Pyright edges used where available; tree-sitter edges as fallback.
//
// Pyright invocation:
//   We write the file content to a temp file (Pyright needs filesystem access),
//   run `pyright --outputjson <tempfile>`, parse the JSON, then delete the temp.
//   This is ~50-100ms per file — acceptable for a background analysis task.

function parsePython(filePath, content) {
  const tsResult = parseWithTreeSitter('python', filePath, content);

  if (!PYRIGHT_AVAILABLE) {
    return tsResult;
  }

  try {
    const pyrightEdges = extractPythonEdgesViaPyright(filePath, content, tsResult.nodes);
    return {
      nodes: tsResult.nodes,
      edges: mergeEdges(pyrightEdges, tsResult.edges),
    };
  } catch (e) {
    process.stderr.write(`[bridge] Pyright error for ${filePath}: ${e.message}\n`);
    return tsResult;
  }
}

function extractPythonEdgesViaPyright(filePath, content, parsedNodes) {
  // Write to temp file
  const tmpDir  = os.tmpdir();
  const tmpFile = path.join(tmpDir, `bridge_py_${process.pid}_${Date.now()}.py`);

  try {
    fs.writeFileSync(tmpFile, content, 'utf8');

    // Pyright --outputjson returns a structured JSON with type info
    const result = spawnSync('pyright', ['--outputjson', tmpFile], {
      encoding: 'utf8',
      timeout:  15000,  // 15s max
    });

    if (!result.stdout) return [];

    let pyrightOutput;
    try { pyrightOutput = JSON.parse(result.stdout); }
    catch (e) { return []; }

    // Pyright's --outputjson gives us diagnostics but NOT call graph directly.
    // To get call targets we use pyright's languageserver JSON-RPC mode for
    // "go to definition" — but that requires a long-lived server.
    //
    // PRAGMATIC APPROACH: We use pyright's type stubs to validate and upgrade
    // the call edges already extracted by tree-sitter.
    // For each tree-sitter edge where receiver type is unknown, check pyright's
    // symbol table output for the receiver variable's declared type.
    //
    // The symbol data we CAN extract from --outputjson:
    //   pyrightOutput.generalDiagnostics: type errors that reveal inferred types
    //
    // Better approach: use pyright as a library (programmatic API)
    return extractEdgesViaPyrightProgrammatic(tmpFile, content, parsedNodes, filePath);

  } finally {
    try { fs.unlinkSync(tmpFile); } catch (e) {}
  }
}

function extractEdgesViaPyrightProgrammatic(tmpFile, content, parsedNodes, originalFilePath) {
  // Use pyright's programmatic API via a sub-script
  // This avoids the complexity of the full language server protocol
  const pyrightScript = `
import sys
import json
from pathlib import Path

try:
    from pyright import run_pyright
    result = run_pyright([sys.argv[1], '--outputjson'])
    print(json.dumps({"success": True, "output": result}))
except ImportError:
    # Fall back to subprocess-based pyright with --verifytypes
    import subprocess
    r = subprocess.run(
        ['pyright', '--outputjson', sys.argv[1]],
        capture_output=True, text=True, timeout=15
    )
    try:
        print(json.dumps({"success": True, "output": json.loads(r.stdout)}))
    except:
        print(json.dumps({"success": False}))
`;

  // Use pyright's node API instead — it's available since pyright ships as npm package
  // Check if pyright node package is available
  let pyrightApi = null;
  try { pyrightApi = require('pyright'); } catch (e) {}

  if (!pyrightApi) {
    // pyright CLI only — extract what we can from type stub inference
    // Use the JSON output to find reportGeneralTypeIssues which often reveals types
    const r = spawnSync('pyright', ['--outputjson', tmpFile], { encoding: 'utf8', timeout: 15000 });
    if (!r.stdout) return [];

    let out;
    try { out = JSON.parse(r.stdout); } catch (e) { return []; }

    // Extract variable type hints from diagnostic messages
    // e.g. 'Cannot access member "validate" for type "PaymentValidator"'
    //      reveals that some variable is typed as PaymentValidator
    const varTypeHints = {};
    if (out.generalDiagnostics) {
      for (const diag of out.generalDiagnostics) {
        const m = diag.message && diag.message.match(/for type "([A-Z]\w*)"/);
        if (m) {
          // We know something at diag.range has type m[1]
          // Map line number to the variable in question
          const line = diag.range && diag.range.start && diag.range.start.line;
          if (line !== undefined) varTypeHints[line] = m[1];
        }
      }
    }

    // Now upgrade tree-sitter edges using these type hints
    // Re-parse the file to associate calls with type hints
    return upgradeEdgesWithTypeHints(content, parsedNodes, varTypeHints, originalFilePath);
  }

  return [];
}

function upgradeEdgesWithTypeHints(content, parsedNodes, varTypeHints, filePath) {
  // Build a line-to-class map from type hints
  // For each call on a line where we know the receiver type, emit a resolved edge
  const lines  = content.split('\n');
  const edges  = [];
  const nodeMap = {};
  for (const n of parsedNodes) nodeMap[n.id] = n;

  // Find calls with receiver.method() pattern
  const callPattern = /(\w+)\.(\w+)\s*\(/g;

  for (let lineIdx = 0; lineIdx < lines.length; lineIdx++) {
    const line      = lines[lineIdx];
    const lineNo    = lineIdx + 1;
    const knownType = varTypeHints[lineIdx];  // pyright told us the type at this line

    let match;
    while ((match = callPattern.exec(line)) !== null) {
      const receiver   = match[1];
      const methodName = match[2];

      // Find which function this line belongs to
      const enclosingNode = findEnclosingNode(parsedNodes, filePath, lineNo);
      if (!enclosingNode) continue;

      let resolvedType = knownType;
      if (!resolvedType && receiver === 'self' && enclosingNode.classContext) {
        resolvedType = enclosingNode.classContext;
      }

      const to = resolvedType
        ? `${resolvedType}.${methodName}`
        : `${receiver}.${methodName}`;

      edges.push({
        from:       enclosingNode.id,
        to,
        callType:   'METHOD_CALL',
        sourceLine: lineNo,
        resolved:   !!resolvedType,
      });
    }
    callPattern.lastIndex = 0;
  }

  return edges;
}

// ═══════════════════════════════════════════════════════════════════════════════
// GO — Tree-sitter (nodes + CC) + go-types-bridge binary (call edges)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Strategy:
//   1. Tree-sitter for node/CC extraction (same as Tier 1).
//   2. go-types-bridge binary (built from GoTypesBridge.go) invoked per-file.
//      The bridge uses go/types + go/ssa to:
//        - Resolve variable types (even for interfaces)
//        - Extract call graph with fully-qualified targets
//   3. Merge results.
//
// The go-types-bridge binary accepts:
//   stdin:  { "filePath": "...", "content": "...", "packageName": "..." }
//   stdout: { "calls": [{ "from": "...", "to": "...", "line": N }] }

function parseGo(filePath, content) {
  const tsResult = parseWithTreeSitter('go', filePath, content);

  if (!GO_BRIDGE_AVAILABLE) {
    return tsResult;
  }

  try {
    const goEdges = extractGoEdgesViaTypesBridge(filePath, content, tsResult.nodes);
    return {
      nodes: tsResult.nodes,
      edges: mergeEdges(goEdges, tsResult.edges),
    };
  } catch (e) {
    process.stderr.write(`[bridge] go-types error for ${filePath}: ${e.message}\n`);
    return tsResult;
  }
}

function extractGoEdgesViaTypesBridge(filePath, content, parsedNodes) {
  // Extract package name from content
  const pkgMatch = content.match(/^package\s+(\w+)/m);
  const packageName = pkgMatch ? pkgMatch[1] : 'main';

  const input  = JSON.stringify({ filePath, content, packageName });
  const result = spawnSync(GO_BRIDGE_PATH, [], {
    input:    input,
    encoding: 'utf8',
    timeout:  20000,   // Go type checking can take a moment
  });

  if (result.status !== 0 || !result.stdout) {
    if (result.stderr) {
      process.stderr.write(`[bridge] go-types-bridge stderr: ${result.stderr}\n`);
    }
    return [];
  }

  let output;
  try { output = JSON.parse(result.stdout); }
  catch (e) { return []; }

  if (!output.calls || !Array.isArray(output.calls)) return [];

  return output.calls.map(call => ({
    from:       call.from,
    to:         call.to,
    callType:   'METHOD_CALL',
    sourceLine: call.line || 0,
    resolved:   true,
  }));
}

// ═══════════════════════════════════════════════════════════════════════════════
// RUBY — Tree-sitter only (unchanged from Tier 1)
// ═══════════════════════════════════════════════════════════════════════════════

function parseRuby(filePath, content) {
  return parseWithTreeSitter('ruby', filePath, content);
}

// ═══════════════════════════════════════════════════════════════════════════════
// JAVASCRIPT — Tree-sitter only (cross-file index handles resolution in Java)
// ═══════════════════════════════════════════════════════════════════════════════

function parseJavaScript(filePath, content) {
  return parseWithTreeSitter('javascript', filePath, content);
}

// ═══════════════════════════════════════════════════════════════════════════════
// TREE-SITTER CORE  (all languages — nodes + CC extraction)
// ═══════════════════════════════════════════════════════════════════════════════

function parseWithTreeSitter(language, filePath, content) {
  const grammar = GRAMMARS[language];
  if (!grammar) throw new Error(`Grammar not available: ${language}`);

  // Normalise to a UTF-8 string. Buffer.toString('utf8') is correct;
  // String(buffer) would produce "[object Object]" for Node.js Buffers.
  let safeContent = typeof content === 'string'
    ? content
    : (Buffer.isBuffer(content) ? content.toString('utf8') : String(content ?? ''));

  // Root cause of "Invalid argument" errors on Alembic migrations and pipeline files:
  // tree-sitter native binding rejects strings that contain NUL bytes (\x00).
  safeContent = safeContent.replace(/\0/g, '');

  // Guard against minified/generated files that can OOM the native parser.
  if (safeContent.length > 500_000) {
    process.stderr.write(`[bridge] WARN: ${filePath} truncated to 500KB (was ${safeContent.length} chars)\n`);
    safeContent = safeContent.substring(0, 500_000);
  }

  parser.setLanguage(grammar);
  let tree;
  try {
    tree = parser.parse(safeContent);
  } catch (e) {
    // Re-initialise so the next file is not poisoned by corrupted parser state.
    // NOTE: parser must be `let` (not `const`) for this assignment to work.
    try {
      parser = new Parser();
      parser.setLanguage(grammar);
      // Retry once with the fresh parser — recovers the triggering file too.
      tree = parser.parse(safeContent);
    } catch (e2) {
      throw new Error(`Tree-sitter error for ${filePath}: ${e2.message}`);
    }
  }

  switch (language) {
    case 'python':     return parsePythonTree(tree, filePath, safeContent);
    case 'go':         return parseGoTree(tree, filePath, safeContent);
    case 'ruby':       return parseRubyTree(tree, filePath, safeContent);
    case 'javascript':
    case 'typescript': return parseJSTree(tree, filePath, safeContent);
    default:           throw new Error(`No tree-sitter parser for: ${language}`);
  }
}

// ─── Python Tree-sitter (Tier 1 implementation, unchanged) ───────────────────

function parsePythonTree(tree, filePath, content) {
  const nodes = [], edges = [];
  const lines = content.split('\n');
  collectPythonFunctions(tree.rootNode, filePath, null, nodes, edges, lines);
  return { nodes, edges };
}

function collectPythonFunctions(node, filePath, currentClass, nodes, edges, lines) {
  for (const child of node.children) {
    if (child.type === 'class_definition') {
      const nameNode = child.children.find(c => c.type === 'identifier');
      const className = nameNode ? nameNode.text : null;
      const bodyNode = child.children.find(c => c.type === 'block');
      if (bodyNode) collectPythonFunctions(bodyNode, filePath, className, nodes, edges, lines);
    } else if (child.type === 'function_definition' || child.type === 'async_function_definition') {
      const isAsync  = child.type === 'async_function_definition';
      const nameNode = child.children.find(c => c.type === 'identifier');
      if (!nameNode) continue;
      const funcName  = nameNode.text;
      const nodeId    = currentClass ? `${filePath}:${currentClass}.${funcName}` : `${filePath}:${funcName}`;
      const startLine = child.startPosition.row + 1;
      const endLine   = child.endPosition.row + 1;
      const decorators = extractPythonDecorators(child);
      const returnType = extractPythonReturnType(child);
      const bodyNode   = child.children.find(c => c.type === 'block');
      const cc         = bodyNode ? computePythonCC(bodyNode) : 1;
      const callEdges  = extractPythonCalls(bodyNode, nodeId, filePath, currentClass, lines);
      edges.push(...callEdges);
      nodes.push({ id: nodeId, label: funcName, filePath, startLine, endLine,
                   returnType: returnType || 'unknown', cyclomaticComplexity: cc,
                   classContext: currentClass, isAsync, decorators });
      if (bodyNode) collectPythonFunctions(bodyNode, filePath, currentClass, nodes, edges, lines);
    } else {
      collectPythonFunctions(child, filePath, currentClass, nodes, edges, lines);
    }
  }
}

function extractPythonDecorators(funcNode) {
  const decorators = [];
  const parent = funcNode.parent;
  if (parent && parent.type === 'decorated_definition') {
    for (const child of parent.children) {
      if (child.type === 'decorator')
        decorators.push(child.text.replace(/^@/, '').split('(')[0].trim());
    }
  }
  return decorators;
}

function extractPythonReturnType(funcNode) {
  let sawArrow = false;
  for (const child of funcNode.children) {
    if (child.type === '->' || child.text === '->') { sawArrow = true; continue; }
    if (sawArrow && child.type !== ':') return child.text;
  }
  return null;
}

function computePythonCC(node) {
  let cc = 1;
  walkNode(node, (n) => {
    switch (n.type) {
      case 'if_statement': case 'for_statement': case 'while_statement':
      case 'with_statement': case 'assert_statement': case 'elif_clause':
      case 'except_clause': case 'conditional_expression': case 'boolean_operator':
        cc++; break;
      case 'list_comprehension': case 'set_comprehension':
      case 'dictionary_comprehension': case 'generator_expression':
        for (const ch of n.children) { if (ch.type === 'if_clause') cc++; }
        break;
    }
  });
  return cc;
}

function extractPythonCalls(bodyNode, fromId, filePath, currentClass, lines) {
  if (!bodyNode) return [];
  const edges = [];
  walkNode(bodyNode, (n) => {
    if (n.type === 'call') {
      const funcNode = n.children[0];
      if (!funcNode) return;
      const callText   = funcNode.text;
      const sourceLine = n.startPosition.row + 1;
      let toId = null;
      if (callText.includes('.')) {
        const parts = callText.split('.');
        const recv  = parts[0], method = parts[parts.length - 1];
        toId = recv === 'self' && currentClass
          ? `${filePath}:${currentClass}.${method}`
          : `${recv}.${method}`;
      } else {
        toId = `${filePath}:${callText}`;
      }
      if (toId && toId !== fromId) edges.push({ from: fromId, to: toId, callType: 'METHOD_CALL', sourceLine });
    }
  });
  return edges;
}

// ─── Go Tree-sitter ───────────────────────────────────────────────────────────

function parseGoTree(tree, filePath, content) {
  const nodes = [], edges = [];
  let packageName = '';
  for (const child of tree.rootNode.children) {
    if (child.type === 'package_clause') {
      const n = child.children.find(c => c.type === 'package_identifier');
      if (n) packageName = n.text;
      break;
    }
  }
  for (const child of tree.rootNode.children) {
    if (child.type === 'function_declaration' || child.type === 'method_declaration') {
      const isMethod     = child.type === 'method_declaration';
      let receiverType   = null;
      if (isMethod) {
        const pl = child.children.find(c => c.type === 'parameter_list');
        if (pl) receiverType = extractGoReceiver(pl);
      }
      const nameNode = child.children.find(c => c.type === 'field_identifier' || c.type === 'identifier');
      if (!nameNode) continue;
      const funcName   = nameNode.text;
      const qualifier  = receiverType || packageName;
      const nodeId     = qualifier ? `${filePath}:${qualifier}.${funcName}` : `${filePath}:${funcName}`;
      const startLine  = child.startPosition.row + 1;
      const endLine    = child.endPosition.row + 1;
      const returnType = extractGoReturnType(child);
      const bodyNode   = child.children.find(c => c.type === 'block');
      const cc         = bodyNode ? computeGoCC(bodyNode) : 1;
      const callEdges  = extractGoCalls(bodyNode, nodeId, filePath, receiverType, packageName);
      edges.push(...callEdges);
      nodes.push({ id: nodeId, label: funcName, filePath, startLine, endLine,
                   returnType: returnType || 'void', cyclomaticComplexity: cc,
                   classContext: receiverType, isAsync: false, decorators: [] });
    }
  }
  return { nodes, edges };
}

function extractGoReceiver(paramList) {
  for (const child of paramList.children) {
    if (child.type === 'parameter_declaration') {
      for (const sub of child.children) {
        if (sub.type === 'pointer_type') {
          const t = sub.children.find(c => c.type === 'type_identifier');
          if (t) return t.text;
        }
        if (sub.type === 'type_identifier') return sub.text;
      }
    }
  }
  return null;
}

function extractGoReturnType(funcNode) {
  const r = funcNode.children.find(c => c.type === 'type_identifier');
  return r ? r.text : null;
}

function computeGoCC(node) {
  let cc = 1;
  walkNode(node, (n) => {
    switch (n.type) {
      case 'if_statement': case 'for_statement': case 'case_clause':
      case 'communication_case': case 'type_case_clause': case 'default_case':
        cc++; break;
      case 'binary_expression':
        if (n.children[1] && (n.children[1].text === '&&' || n.children[1].text === '||')) cc++;
        break;
    }
  });
  return cc;
}

function extractGoCalls(bodyNode, fromId, filePath, receiverType, packageName) {
  if (!bodyNode) return [];
  const edges = [];
  walkNode(bodyNode, (n) => {
    if (n.type === 'call_expression') {
      const funcNode = n.children[0];
      if (!funcNode) return;
      const callText   = funcNode.text;
      const sourceLine = n.startPosition.row + 1;
      let toId = callText.includes('.')
        ? callText.split('.').reduce((_, p, i, arr) => i === arr.length - 1 ? `${arr[i-1]}.${p}` : '')
        : packageName ? `${filePath}:${packageName}.${callText}` : `${filePath}:${callText}`;
      if (toId && toId !== fromId) edges.push({ from: fromId, to: toId, callType: 'METHOD_CALL', sourceLine });
    }
  });
  return edges;
}

// ─── Ruby Tree-sitter ─────────────────────────────────────────────────────────

function parseRubyTree(tree, filePath, content) {
  const nodes = [], edges = [];
  collectRubyMethods(tree.rootNode, filePath, null, nodes, edges);
  return { nodes, edges };
}

function collectRubyMethods(node, filePath, currentClass, nodes, edges) {
  for (const child of node.children) {
    if (child.type === 'class' || child.type === 'module') {
      const cn  = child.children.find(c => c.type === 'constant');
      const cls = cn ? cn.text : null;
      const body = child.children.find(c => c.type === 'body_statement');
      if (body) collectRubyMethods(body, filePath, cls, nodes, edges);
    } else if (child.type === 'method' || child.type === 'singleton_method') {
      const nameNode = child.children.find(c => c.type === 'identifier');
      if (!nameNode) continue;
      const funcName  = nameNode.text;
      const nodeId    = currentClass ? `${filePath}:${currentClass}.${funcName}` : `${filePath}:${funcName}`;
      const startLine = child.startPosition.row + 1;
      const endLine   = child.endPosition.row + 1;
      const bodyNode  = child.children.find(c => c.type === 'body_statement');
      const cc        = bodyNode ? computeRubyCC(bodyNode) : 1;
      const callEdges = extractRubyCalls(bodyNode, nodeId, filePath, currentClass);
      edges.push(...callEdges);
      nodes.push({ id: nodeId, label: funcName, filePath, startLine, endLine,
                   returnType: 'unknown', cyclomaticComplexity: cc,
                   classContext: currentClass, isAsync: false, decorators: [] });
      if (bodyNode) collectRubyMethods(bodyNode, filePath, currentClass, nodes, edges);
    } else {
      collectRubyMethods(child, filePath, currentClass, nodes, edges);
    }
  }
}

function computeRubyCC(node) {
  let cc = 1;
  walkNode(node, (n) => {
    switch (n.type) {
      case 'if': case 'unless': case 'elsif': case 'while':
      case 'until': case 'for': case 'rescue': case 'when':
      case 'conditional': case 'and': case 'or': cc++; break;
      case 'binary':
        if (n.children[1] && (n.children[1].text === '&&' || n.children[1].text === '||')) cc++;
        break;
    }
  });
  return cc;
}

function extractRubyCalls(bodyNode, fromId, filePath, currentClass) {
  if (!bodyNode) return [];
  const edges = [];
  walkNode(bodyNode, (n) => {
    if (n.type === 'call') {
      const recv   = n.children[0];
      const method = n.children[n.children.length - 1];
      if (!method) return;
      const sourceLine = n.startPosition.row + 1;
      let toId;
      if (recv && recv.text === 'self' && currentClass)
        toId = `${filePath}:${currentClass}.${method.text}`;
      else if (recv && recv.type === 'constant')
        toId = `${recv.text}.${method.text}`;
      else
        toId = `${filePath}:${method.text}`;
      if (toId && toId !== fromId) edges.push({ from: fromId, to: toId, callType: 'METHOD_CALL', sourceLine });
    }
  });
  return edges;
}

// ─── JS/TS Tree-sitter ────────────────────────────────────────────────────────

function parseJSTree(tree, filePath, content) {
  const nodes = [], edges = [];
  collectJSFunctions(tree.rootNode, filePath, null, nodes, edges);
  return { nodes, edges };
}

function collectJSFunctions(node, filePath, currentClass, nodes, edges) {
  for (const child of node.children) {
    if (child.type === 'class_declaration' || child.type === 'class') {
      const nameNode = child.children.find(c => c.type === 'identifier' || c.type === 'type_identifier');
      const cls      = nameNode ? nameNode.text : null;
      const body     = child.children.find(c => c.type === 'class_body');
      if (body) collectJSFunctions(body, filePath, cls, nodes, edges);
    } else if (isJSFunctionNode(child)) {
      const info = extractJSFunctionInfo(child, currentClass, filePath, null);
      if (info) {
        const callEdges = extractJSCalls(child, info.id, filePath, currentClass);
        edges.push(...callEdges);
        nodes.push(info);
      }
      const body = child.children.find(c => c.type === 'statement_block');
      if (body) collectJSFunctions(body, filePath, currentClass, nodes, edges);
    } else if (child.type === 'lexical_declaration' || child.type === 'variable_declaration') {
      for (const decl of child.children) {
        if (decl.type === 'variable_declarator') {
          const nameNode  = decl.children[0];
          const valueNode = decl.children.find(c => c.type === 'arrow_function' || c.type === 'function');
          if (nameNode && valueNode) {
            const info = extractJSFunctionInfo(valueNode, currentClass, filePath, nameNode.text);
            if (info) {
              const callEdges = extractJSCalls(valueNode, info.id, filePath, currentClass);
              edges.push(...callEdges);
              nodes.push(info);
            }
          }
        }
      }
    } else if (child.type === 'method_definition' || child.type === 'method_signature') {
      const info = extractJSFunctionInfo(child, currentClass, filePath, null);
      if (info) {
        const callEdges = extractJSCalls(child, info.id, filePath, currentClass);
        edges.push(...callEdges);
        nodes.push(info);
      }
    } else {
      collectJSFunctions(child, filePath, currentClass, nodes, edges);
    }
  }
}

function isJSFunctionNode(node) {
  return node.type === 'function_declaration' || node.type === 'generator_function_declaration'
    || node.type === 'method_definition' || node.type === 'method_signature';
}

function extractJSFunctionInfo(node, currentClass, filePath, overrideName) {
  let funcName = overrideName;
  if (!funcName) {
    const nameNode = node.children.find(c =>
      c.type === 'identifier' || c.type === 'property_identifier' || c.type === 'type_identifier');
    funcName = nameNode ? nameNode.text : null;
  }
  if (!funcName) return null;
  const isAsync    = node.children.some(c => c.text === 'async');
  const nodeId     = currentClass ? `${filePath}:${currentClass}.${funcName}` : `${filePath}:${funcName}`;
  const startLine  = node.startPosition.row + 1;
  const endLine    = node.endPosition.row + 1;
  const returnType = extractTSReturnType(node);
  const decorators = extractTSDecorators(node);
  const bodyNode   = node.children.find(c => c.type === 'statement_block' || c.type === 'expression_body');
  const cc         = bodyNode ? computeJSCC(bodyNode) : 1;
  return { id: nodeId, label: funcName, filePath, startLine, endLine,
           returnType: returnType || 'unknown', cyclomaticComplexity: cc,
           classContext: currentClass, isAsync, decorators };
}

function extractTSReturnType(funcNode) {
  const ta = funcNode.children.find(c => c.type === 'type_annotation');
  if (ta) { const t = ta.children.find(c => c.type !== ':'); if (t) return t.text; }
  return null;
}

function extractTSDecorators(funcNode) {
  const decorators = [];
  const parent = funcNode.parent;
  if (!parent) return decorators;
  for (const sib of parent.children) {
    if (sib.type === 'decorator' && sib.endPosition.row < funcNode.startPosition.row) {
      const n = sib.children.find(c => c.type === 'identifier' || c.type === 'call_expression');
      if (n) decorators.push(n.text.split('(')[0]);
    }
  }
  return decorators;
}

function computeJSCC(node) {
  let cc = 1;
  walkNode(node, (n) => {
    switch (n.type) {
      case 'if_statement': case 'for_statement': case 'for_in_statement':
      case 'for_of_statement': case 'while_statement': case 'do_statement':
      case 'switch_case': case 'catch_clause': case 'conditional_expression':
      case 'logical_expression': cc++; break;
      case 'binary_expression':
        if (n.children[1] && ['&&','||','??'].includes(n.children[1].text)) cc++;
        break;
    }
  });
  return cc;
}

function extractJSCalls(bodyNode, fromId, filePath, currentClass) {
  if (!bodyNode) return [];
  const edges = [];
  walkNode(bodyNode, (n) => {
    if (n.type === 'call_expression') {
      const funcNode = n.children[0];
      if (!funcNode) return;
      const sourceLine = n.startPosition.row + 1;
      const callText   = funcNode.text;
      let toId = null;
      if (callText.startsWith('this.') && currentClass) {
        toId = `${filePath}:${currentClass}.${callText.replace('this.', '').split('(')[0]}`;
      } else if (callText.includes('.')) {
        const parts = callText.split('.');
        toId = `${parts[0]}.${parts[parts.length - 1]}`;
      } else {
        toId = `${filePath}:${callText}`;
      }
      if (toId && toId !== fromId) edges.push({ from: fromId, to: toId, callType: 'METHOD_CALL', sourceLine });
    }
  });
  return edges;
}

// ─── Shared utilities ─────────────────────────────────────────────────────────

function walkNode(node, visitor) {
  if (!node) return;
  if (visitor(node) === false) return;
  for (const child of node.children) walkNode(child, visitor);
}

function findEnclosingNode(nodes, filePath, lineNo) {
  // Find the function that contains this line number
  for (const node of nodes) {
    if (node.filePath === filePath && node.startLine <= lineNo && node.endLine >= lineNo) {
      return node;
    }
  }
  return null;
}

// ─── Module exports (for HTTP server) ─────────────────────────────────────────
module.exports = { parseFile, TIER2_CAPABILITIES };