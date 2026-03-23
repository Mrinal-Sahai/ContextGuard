#!/usr/bin/env node
'use strict';
/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DART BRIDGE SERVER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * HTTP server that wraps the Dart Analysis Server (LSP) and tree-sitter Dart
 * parser. Runs in its own container so the main Spring Boot image needs no
 * Flutter SDK.
 *
 * Endpoints:
 *   GET  /health       → { status, dartAvailable, dartGrammar }
 *   POST /index-batch  → { files:{path→content} }
 *                      → { symbols, importAliases, variableTypes, error }
 *   POST /analyze      → { filePath, content, allFiles:{path→content} }
 *                      → { nodes, edges, error }
 *                        edges are RAW: { from, toFile, toLine, callType, sourceLine }
 *                        Java resolves toFile+toLine → nodeId via CrossFileSymbolIndex
 *
 * Port: DART_BRIDGE_PORT env var (default 3001)
 */

const http   = require('http');
const { spawn }  = require('child_process');
const path   = require('path');
const fs     = require('fs');
const os     = require('os');
const crypto = require('crypto');

const Parser = require('tree-sitter');
const { parseDart, DART_GRAMMAR_AVAILABLE } = require('./dart-parser');

const PORT              = parseInt(process.env.DART_BRIDGE_PORT)          || 3001;
const FLUTTER_SDK_PATH  = process.env.DART_ANALYSIS_FLUTTER_SDK_PATH      || '/opt/flutter';
const TIMEOUT_MS        = parseInt(process.env.DART_ANALYSIS_TIMEOUT_MS)  || 15000;
const MAX_CALLS         = parseInt(process.env.DART_ANALYSIS_MAX_CALLS_PER_FILE) || 200;

// ─── LSP client state ─────────────────────────────────────────────────────────

let lspProcess    = null;
let lspReady      = false;
let lspIdCounter  = 1;
const pendingLsp  = new Map();   // id → { resolve, reject, timer }
let   lspBuffer   = Buffer.alloc(0);

// ─── LSP protocol ─────────────────────────────────────────────────────────────

function onLspData(chunk) {
  lspBuffer = Buffer.concat([lspBuffer, chunk]);
  while (true) {
    const sep = lspBuffer.indexOf('\r\n\r\n');
    if (sep === -1) break;
    const header = lspBuffer.slice(0, sep).toString('utf8');
    const clMatch = header.match(/Content-Length:\s*(\d+)/i);
    if (!clMatch) { lspBuffer = lspBuffer.slice(sep + 4); continue; }
    const len = parseInt(clMatch[1]);
    if (lspBuffer.length < sep + 4 + len) break;
    const jsonStr = lspBuffer.slice(sep + 4, sep + 4 + len).toString('utf8');
    lspBuffer = lspBuffer.slice(sep + 4 + len);
    try { routeLspMessage(JSON.parse(jsonStr)); }
    catch (e) { process.stderr.write(`[dart-bridge] LSP parse error: ${e.message}\n`); }
  }
}

function routeLspMessage(msg) {
  if (msg.id !== undefined && msg.id !== null) {
    const p = pendingLsp.get(msg.id);
    if (p) {
      clearTimeout(p.timer);
      pendingLsp.delete(msg.id);
      if (msg.error) p.reject(new Error(msg.error.message || JSON.stringify(msg.error)));
      else           p.resolve(msg.result);
    }
  }
  // Notifications (no id) — ignore (diagnostics, etc.)
}

function writeLsp(obj) {
  const body   = Buffer.from(JSON.stringify(obj), 'utf8');
  const header = `Content-Length: ${body.length}\r\n\r\n`;
  lspProcess.stdin.write(header);
  lspProcess.stdin.write(body);
}

function lspRequest(method, params) {
  return new Promise((resolve, reject) => {
    const id    = lspIdCounter++;
    const timer = setTimeout(() => {
      pendingLsp.delete(id);
      reject(new Error(`LSP timeout: ${method}`));
    }, TIMEOUT_MS);
    pendingLsp.set(id, { resolve, reject, timer });
    writeLsp({ jsonrpc: '2.0', id, method, params });
  });
}

function lspNotify(method, params) {
  writeLsp({ jsonrpc: '2.0', method, params });
}

// ─── Dart Analysis Server lifecycle ───────────────────────────────────────────

async function startDartServer() {
  const dartBin = path.join(FLUTTER_SDK_PATH, 'bin', 'dart');
  if (!fs.existsSync(dartBin)) {
    throw new Error(`dart binary not found at ${dartBin}`);
  }

  const home = process.env.HOME || '/home/contextguard';
  lspProcess = spawn(dartBin, ['language-server', '--protocol=lsp'], {
    env: {
      ...process.env,
      FLUTTER_ROOT:               FLUTTER_SDK_PATH,
      PUB_CACHE:                  path.join(home, '.pub-cache'),
      FLUTTER_SUPPRESS_ANALYTICS: 'true',
      DART_DISABLE_ANALYTICS:     '1',
    },
  });

  lspBuffer = Buffer.alloc(0);
  lspProcess.stdout.on('data', onLspData);
  lspProcess.stderr.on('data', chunk =>
    process.stderr.write(`[dart-lsp] ${chunk.toString()}`));

  lspProcess.on('exit', code => {
    process.stderr.write(`[dart-bridge] Dart Analysis Server exited (code=${code})\n`);
    lspReady = false;
    lspProcess = null;
    for (const p of pendingLsp.values()) {
      clearTimeout(p.timer);
      p.reject(new Error('Dart Analysis Server died'));
    }
    pendingLsp.clear();
  });

  // Give the process a moment to start
  await sleep(500);
  if (!lspProcess) throw new Error('Dart Analysis Server exited immediately');

  // LSP handshake
  await lspRequest('initialize', {
    processId:        process.pid,
    clientInfo:       { name: 'ContextGuard', version: '1.0' },
    rootUri:          null,
    workspaceFolders: null,
    capabilities: {
      textDocument: {
        synchronization: {
          dynamicRegistration: false,
          willSave: false, willSaveWaitUntil: false, didSave: false,
        },
      },
    },
  });
  lspNotify('initialized', {});
  lspReady = true;
  process.stderr.write('[dart-bridge] Dart Analysis Server ready\n');
}

// ─── Temp workspace ───────────────────────────────────────────────────────────

function createWorkspace(files) {
  const dir = path.join(os.tmpdir(),
    `contextguard_dart_${crypto.randomBytes(8).toString('hex')}`);
  fs.mkdirSync(dir, { recursive: true });
  for (const [fp, content] of Object.entries(files)) {
    const rel    = fp.replace(/^[/\\]/, '');
    const target = path.join(dir, rel);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content, 'utf8');
  }
  fs.writeFileSync(
    path.join(dir, 'pubspec.yaml'),
    "name: contextguard_analysis\nenvironment:\n  sdk: '>=2.17.0 <4.0.0'\n"
  );
  return dir;
}

function rmWorkspace(dir) {
  try { fs.rmSync(dir, { recursive: true, force: true }); } catch (_) {}
}

function tempPath(dir, originalPath) {
  return path.join(dir, originalPath.replace(/^[/\\]/, ''));
}

function uriToRelPath(uri, workspaceDir) {
  try {
    const decoded    = decodeURIComponent(uri.replace(/^file:\/\//, ''));
    const baseName   = path.basename(workspaceDir);
    const idx        = decoded.indexOf(baseName);
    if (idx === -1)  return path.basename(decoded);
    // +1 for the trailing separator after the workspace dir name
    return decoded.slice(idx + baseName.length + 1);
  } catch (_) { return null; }
}

// ─── Symbol extraction (Pass 1 — regex, mirrors DartAnalysisBridgeService) ────

const RE_CLASS    = /^(?:abstract\s+)?(?:class|mixin|extension)\s+(\w+)/;
const RE_METHOD   = /^(?:([\w<>?,\s]+?)\s+)?(\w+)\s*\([^)]*\)\s*(?:async\s*)?[{=>]/;
const RE_IMPORT   = /^import\s+['"]([^'"]+)['"]\s*(?:as\s+(\w+))?\s*(?:show\s+([\w,\s]+))?\s*;/gm;
const RE_FIELD    = /(?:final|late|const)?\s*([A-Z]\w*(?:<[^>]+>)?)\s+(\w+)\s*[;=]/g;
const RE_CTOR_ASS = /(?:var|final|late)\s+(\w+)\s*=\s*([A-Z]\w*)\s*[.(]/g;
const SKIP_KW     = new Set(['if','while','for','switch','catch','class','return','throw','new','assert']);

function snakeToPascal(s) {
  return s.split('_').map(p => p.charAt(0).toUpperCase() + p.slice(1)).join('');
}

function extractSymbols(files) {
  const symbols = [], importAliases = [], variableTypes = [];

  for (const [filePath, content] of Object.entries(files)) {
    const lines = content.split('\n');
    let cls = null, depth = 0, clsDepth = -1;

    for (const line of lines) {
      const t = line.trim();

      const cm = t.match(RE_CLASS);
      if (cm) {
        cls = cm[1]; clsDepth = depth;
        symbols.push({ nodeId: `${filePath}:${cls}`, label: cls, classContext: null, filePath, language: 'dart' });
      }

      const mm = t.match(RE_METHOD);
      if (mm && cls && !SKIP_KW.has(mm[2])) {
        const nodeId = `${filePath}:${cls}.${mm[2]}`;
        symbols.push({ nodeId, label: mm[2], classContext: cls, filePath, language: 'dart' });
      }

      for (const ch of t) {
        if (ch === '{') depth++;
        if (ch === '}') { depth--; if (cls !== null && depth <= clsDepth) { cls = null; clsDepth = -1; } }
      }
    }

    // Import aliases
    RE_IMPORT.lastIndex = 0;
    let m;
    while ((m = RE_IMPORT.exec(content)) !== null) {
      const stem = m[1].replace(/.*[/:]/, '').replace('.dart', '');
      const canonical = snakeToPascal(stem);
      if (m[2]) {
        importAliases.push({ filePath, alias: m[2].trim(), canonical });
      } else {
        importAliases.push({ filePath, alias: canonical, canonical });
        importAliases.push({ filePath, alias: stem, canonical });
      }
      if (m[3]) {
        for (const c of m[3].split(',')) {
          const cls2 = c.trim();
          if (cls2) importAliases.push({ filePath, alias: cls2, canonical: cls2 });
        }
      }
    }

    // Field types
    RE_FIELD.lastIndex = 0;
    while ((m = RE_FIELD.exec(content)) !== null) {
      const typeName = m[1].split('<')[0];
      const varName  = m[2];
      if (/^[A-Z]/.test(typeName)) {
        variableTypes.push({ filePath, varName, typeName });
        if (varName.startsWith('_'))
          variableTypes.push({ filePath, varName: varName.slice(1), typeName });
      }
    }

    // Constructor assignments
    RE_CTOR_ASS.lastIndex = 0;
    while ((m = RE_CTOR_ASS.exec(content)) !== null) {
      variableTypes.push({ filePath, varName: m[1], typeName: m[2] });
    }
  }

  return { symbols, importAliases, variableTypes };
}

// ─── Node extraction (tree-sitter) ────────────────────────────────────────────

let tsParser = null;

function getTreeSitterParser() {
  if (tsParser) return tsParser;
  tsParser = new Parser();
  tsParser.setLanguage(require('tree-sitter-dart'));
  return tsParser;
}

function extractNodes(filePath, content) {
  if (DART_GRAMMAR_AVAILABLE) {
    try {
      const p    = getTreeSitterParser();
      const tree = p.parse(content);
      return parseDart(tree, filePath, content);
    } catch (e) {
      process.stderr.write(`[dart-bridge] tree-sitter parse failed for ${filePath}: ${e.message}\n`);
    }
  }
  return { nodes: [], edges: [] };
}

// ─── LSP edge resolution (Pass 2) ─────────────────────────────────────────────

const RE_CALL = /(?:(\w+)\.)?(\w+)\s*\(/g;

async function resolveEdges(filePath, content, workspaceDir, nodes) {
  if (!lspReady || !lspProcess) return [];

  const tPath  = tempPath(workspaceDir, filePath);
  const fileUri = `file://${tPath}`;

  // Tell the server about this file
  lspNotify('textDocument/didOpen', {
    textDocument: { uri: fileUri, languageId: 'dart', version: 1, text: content },
  });
  await sleep(100);

  // Line → enclosing node map
  const lineMap = new Map();
  for (const n of nodes) {
    for (let l = n.startLine; l <= n.endLine; l++) {
      if (!lineMap.has(l)) lineMap.set(l, n);
    }
  }

  const edges     = [];
  const lines     = content.split('\n');
  let   callCount = 0;

  for (let li = 0; li < lines.length && callCount < MAX_CALLS; li++) {
    const enc = lineMap.get(li + 1);
    if (!enc) continue;

    RE_CALL.lastIndex = 0;
    let m;
    while ((m = RE_CALL.exec(lines[li])) !== null && callCount < MAX_CALLS) {
      const method = m[2];
      if (SKIP_KW.has(method)) continue;

      const col = m.index + (m[1] ? m[1].length + 1 : 0);

      try {
        const result = await lspRequest('textDocument/definition', {
          textDocument: { uri: fileUri },
          position:     { line: li, character: col },
        });
        if (result) {
          const locations = Array.isArray(result) ? result : (result.uri ? [result] : []);
          if (locations.length > 0) {
            const loc     = locations[0];
            const toFile  = uriToRelPath(loc.uri, workspaceDir);
            const toLine  = ((loc.range && loc.range.start && loc.range.start.line) || 0) + 1;
            if (toFile && toFile !== filePath) {
              edges.push({ from: enc.id, toFile, toLine, callType: 'METHOD_CALL', sourceLine: li + 1 });
            }
          }
        }
      } catch (_) { /* timeout or server error — skip */ }

      callCount++;
    }
  }
  return edges;
}

// ─── HTTP helpers ─────────────────────────────────────────────────────────────

function readBody(req) {
  return new Promise((resolve, reject) => {
    const parts = [];
    req.on('data', c => parts.push(c));
    req.on('end',  () => resolve(Buffer.concat(parts).toString('utf8')));
    req.on('error', reject);
  });
}

function json(res, code, body) {
  const p = JSON.stringify(body);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(p) });
  res.end(p);
}

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

// ─── HTTP server ──────────────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
  try {
    // ── GET /health ──────────────────────────────────────────────────────────
    if (req.method === 'GET' && req.url === '/health') {
      json(res, 200, {
        status:       'ok',
        dartAvailable: lspReady && lspProcess !== null,
        dartGrammar:   DART_GRAMMAR_AVAILABLE,
      });
      return;
    }

    // ── POST /index-batch ────────────────────────────────────────────────────
    if (req.method === 'POST' && req.url === '/index-batch') {
      const { files } = JSON.parse(await readBody(req));
      try {
        const result = extractSymbols(files || {});
        json(res, 200, { ...result, error: null });
      } catch (e) {
        json(res, 200, { symbols: [], importAliases: [], variableTypes: [], error: e.message });
      }
      return;
    }

    // ── POST /analyze ────────────────────────────────────────────────────────
    if (req.method === 'POST' && req.url === '/analyze') {
      const { filePath, content, allFiles } = JSON.parse(await readBody(req));
      const filesMap = allFiles || { [filePath]: content };
      let wsDir = null;
      try {
        wsDir = createWorkspace(filesMap);
        const { nodes } = extractNodes(filePath, content);
        const edges     = await resolveEdges(filePath, content, wsDir, nodes);
        json(res, 200, { nodes, edges, error: null });
      } catch (e) {
        json(res, 200, { nodes: [], edges: [], error: e.message });
      } finally {
        if (wsDir) rmWorkspace(wsDir);
      }
      return;
    }

    res.writeHead(404); res.end();
  } catch (e) {
    if (!res.headersSent) json(res, 500, { error: e.message });
  }
});

// ─── Startup ──────────────────────────────────────────────────────────────────

async function main() {
  try {
    await startDartServer();
  } catch (e) {
    process.stderr.write(`[dart-bridge] Dart Analysis Server unavailable: ${e.message}\n`);
    process.stderr.write('[dart-bridge] Serving tree-sitter-only results (no LSP edge resolution)\n');
  }

  server.listen(PORT, '0.0.0.0', () => {
    process.stderr.write(
      `[dart-bridge] HTTP server on port ${PORT} ` +
      `(lsp=${lspReady}, grammar=${DART_GRAMMAR_AVAILABLE})\n`
    );
  });
}

process.on('SIGTERM', () => {
  if (lspProcess) {
    try {
      lspNotify('shutdown', {});
      lspNotify('exit', {});
    } catch (_) {}
    lspProcess.kill();
  }
  server.close(() => process.exit(0));
});

main();
