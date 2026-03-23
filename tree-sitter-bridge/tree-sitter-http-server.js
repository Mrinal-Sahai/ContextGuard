#!/usr/bin/env node
'use strict';
/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TREE-SITTER HTTP SERVER
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * HTTP wrapper around tree-sitter-bridge.js and index-batch-handler.js.
 * Replaces the stdin/stdout subprocess protocol with a simple REST API
 * so tree-sitter parsing runs in its own container.
 *
 * Endpoints:
 *   GET  /health       → { status, tsc, pyright, goTypes, dart }
 *   POST /parse        → { language, filePath, content } → { nodes, edges, error }
 *   POST /index-batch  → { language, files:{path→content} } → { symbols, importAliases, variableTypes, error }
 *
 * Port: PORT env var (default 3000)
 */

const http = require('http');
const { parseFile, TIER2_CAPABILITIES } = require('./tree-sitter-bridge');
const { handleIndexBatch }              = require('./index-batch-handler');

const PORT = parseInt(process.env.PORT) || 3000;

// ─── HTTP helpers ─────────────────────────────────────────────────────────────

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end',  () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function json(res, statusCode, body) {
  const payload = JSON.stringify(body);
  res.writeHead(statusCode, {
    'Content-Type':   'application/json',
    'Content-Length': Buffer.byteLength(payload),
  });
  res.end(payload);
}

// ─── Request handler ──────────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
  try {
    // ── GET /health ────────────────────────────────────────────────────────────
    if (req.method === 'GET' && req.url === '/health') {
      json(res, 200, { status: 'ok', ...TIER2_CAPABILITIES });
      return;
    }

    // ── POST /parse ────────────────────────────────────────────────────────────
    if (req.method === 'POST' && req.url === '/parse') {
      const body = await readBody(req);
      const { language, filePath, content } = JSON.parse(body);
      try {
        const result = parseFile(language, filePath, content);
        json(res, 200, { nodes: result.nodes, edges: result.edges, error: null });
      } catch (e) {
        json(res, 200, { nodes: [], edges: [], error: e.message });
      }
      return;
    }

    // ── POST /index-batch ──────────────────────────────────────────────────────
    if (req.method === 'POST' && req.url === '/index-batch') {
      const body    = await readBody(req);
      const request = JSON.parse(body);   // { language, files: { path → content } }
      try {
        const result = await handleIndexBatch(request);
        json(res, 200, {
          symbols:       result.symbols       || [],
          importAliases: result.importAliases || [],
          variableTypes: result.variableTypes || [],
          error:         result.error         || null,
        });
      } catch (e) {
        json(res, 200, {
          symbols: [], importAliases: [], variableTypes: [], error: e.message,
        });
      }
      return;
    }

    res.writeHead(404);
    res.end();
  } catch (e) {
    if (!res.headersSent) {
      json(res, 500, { error: e.message });
    }
  }
});

// ─── Startup ──────────────────────────────────────────────────────────────────

server.listen(PORT, '0.0.0.0', () => {
  process.stderr.write(
    `[tree-sitter-bridge] HTTP server listening on port ${PORT} ` +
    `(tsc=${TIER2_CAPABILITIES.tsc}, pyright=${TIER2_CAPABILITIES.pyright}, ` +
    `go-types=${TIER2_CAPABILITIES.goTypes}, dart=${TIER2_CAPABILITIES.dart})\n`
  );
});

process.on('SIGTERM', () => {
  server.close(() => process.exit(0));
});
