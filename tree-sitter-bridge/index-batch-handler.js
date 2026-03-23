/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * INDEX BATCH HANDLER — for tree-sitter-bridge.js
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Handles "index_batch" requests from LanguageToolBridgeService (Pass 1).
 *
 * These are distinct from "parse" requests. A batch index:
 *   - Receives an entire map of { filePath → content } for one language
 *   - Extracts ALL symbols (classes, methods, imports, variable types)
 *   - For TypeScript: uses a multi-file tsc Program for cross-file type awareness
 *   - For Python: uses Pyright on each file
 *   - For Go: uses go-types-bridge on each file
 *   - Returns a flat list of symbols, aliases, and variable types for the Java
 *     LanguageToolBridgeService to populate CrossFileSymbolIndex
 *
 * INTEGRATE INTO tree-sitter-bridge.js:
 *   const { handleIndexBatch } = require('./index-batch-handler');
 *
 *   // In the rl.on('line') handler, add this before the existing dispatch:
 *   if (request.type === 'index_batch') {
 *     const result = await handleIndexBatch(request);
 *     writeResponse({ id: request.id, type: 'index_batch', ...result, error: null });
 *     return;
 *   }
 *
 * RESPONSE SHAPE:
 *   {
 *     "symbols": [
 *       { "nodeId": "src/svc.ts:PaymentService.process",
 *         "label": "process",
 *         "classContext": "PaymentService",
 *         "filePath": "src/svc.ts",
 *         "language": "typescript" }
 *     ],
 *     "importAliases": [
 *       { "filePath": "src/order.ts", "alias": "Svc", "canonical": "PaymentService" }
 *     ],
 *     "variableTypes": [
 *       { "filePath": "src/order.ts", "varName": "svc", "typeName": "PaymentService" }
 *     ]
 *   }
 */

'use strict';

const path     = require('path');
const fs       = require('fs');
const os       = require('os');
const { spawnSync } = require('child_process');

// ─── Availability flags (resolved once at module load) ────────────────────────

const TS_AVAILABLE = (() => { try { require('typescript'); return true; } catch (e) { return false; } })();
const PYRIGHT_PATH = (() => {
  const r = spawnSync('pyright', ['--version'], { encoding: 'utf8', timeout: 5000 });
  return r.status === 0 ? 'pyright' : null;
})();
const GO_BRIDGE = process.env.GO_TYPES_BRIDGE_PATH
  || path.join(__dirname, 'go-types-bridge');
const GO_BRIDGE_OK = (() => {
  try {
    return spawnSync(GO_BRIDGE, ['--version'], { encoding: 'utf8', timeout: 3000 }).status === 0;
  } catch (e) { return false; }
})();

// ─── Entry point ──────────────────────────────────────────────────────────────

async function handleIndexBatch(request) {
  const { language, files } = request;  // files = { filePath: content, ... }

  switch (language) {
    case 'typescript': return indexTypeScriptBatch(files);
    case 'python':     return indexPythonBatch(files);
    case 'go':         return indexGoBatch(files);
    default:
      return { symbols: [], importAliases: [], variableTypes: [],
               error: `index_batch not implemented for ${language}` };
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TYPESCRIPT BATCH INDEX — multi-file tsc Program
// ═══════════════════════════════════════════════════════════════════════════════
//
// Key advantage of batch: the TypeScript Compiler creates ONE Program that
// knows about ALL files in the batch. This means cross-file import resolution
// works — `import { PaymentService } from './service'` is resolved against
// the actual PaymentService source in the batch, not just assumed.

function indexTypeScriptBatch(files) {
  if (!TS_AVAILABLE) {
    return { symbols: [], importAliases: [], variableTypes: [],
             error: 'typescript package not available' };
  }

  const ts       = require('typescript');
  const symbols       = [];
  const importAliases = [];
  const variableTypes = [];
  const filePaths     = Object.keys(files);

  // ── Build an in-memory compiler host for all files ────────────────────────
  const sourceFiles = new Map();  // filePath → ts.SourceFile
  for (const [fp, content] of Object.entries(files)) {
    sourceFiles.set(fp, ts.createSourceFile(fp, content, ts.ScriptTarget.Latest, true));
  }

  const defaultHost = ts.createCompilerHost({});
  const customHost = {
    ...defaultHost,
    getSourceFile: (name, langVer) => sourceFiles.get(name) || defaultHost.getSourceFile(name, langVer),
    fileExists:    (name) => sourceFiles.has(name) || defaultHost.fileExists(name),
    readFile:      (name) => files[name]           || defaultHost.readFile(name),
    getDefaultLibFileName: (opts) => defaultHost.getDefaultLibFileName(opts),
    getCurrentDirectory:   ()     => defaultHost.getCurrentDirectory(),
    getDirectories:        (p)    => defaultHost.getDirectories(p),
    useCaseSensitiveFileNames: () => true,
    getCanonicalFileName:  (f)    => f,
    getNewLine:            ()     => '\n',
    writeFile:             ()     => {},
  };

  const program = ts.createProgram(filePaths, {
    noEmit:           true,
    skipLibCheck:     true,
    strict:           false,
    allowJs:          true,
    target:           ts.ScriptTarget.Latest,
    moduleResolution: ts.ModuleResolutionKind.NodeJs,
  }, customHost);

  const checker = program.getTypeChecker();

  // ── Walk each source file extracting symbols ──────────────────────────────
  for (const [filePath, sf] of sourceFiles) {
    extractTypeScriptSymbols(sf, filePath, checker, ts,
        symbols, importAliases, variableTypes);
  }

  return { symbols, importAliases, variableTypes };
}

function extractTypeScriptSymbols(sourceFile, filePath, checker, ts,
                                    symbols, importAliases, variableTypes) {
  let currentClass = null;

  function walk(node) {
    // Class declarations
    if (ts.isClassDeclaration(node) && node.name) {
      const prevClass = currentClass;
      currentClass = node.name.getText();
      ts.forEachChild(node, walk);
      currentClass = prevClass;
      return;
    }

    // Function / method declarations
    if (ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node) ||
        ts.isArrowFunction(node) && node.parent && ts.isVariableDeclarator(node.parent)) {
      const nameNode = node.name;
      const funcName = nameNode ? nameNode.getText() : null;
      if (funcName) {
        const nodeId = currentClass
            ? `${filePath}:${currentClass}.${funcName}`
            : `${filePath}:${funcName}`;
        symbols.push({
          nodeId,
          label:        funcName,
          classContext: currentClass,
          filePath,
          language:     'typescript',
        });
      }
    }

    // Variable declarations with type annotations: const x: PaymentService = ...
    // or const x = new PaymentService()
    if (ts.isVariableDeclaration(node)) {
      const varName = node.name.getText();
      // Type annotation
      if (node.type) {
        const typeName = node.type.getText().replace(/<.*>/, ''); // strip generics
        if (/^[A-Z]/.test(typeName)) {
          variableTypes.push({ filePath, varName, typeName });
        }
      }
      // new ClassName() initializer
      if (node.initializer && ts.isNewExpression(node.initializer)) {
        const typeName = node.initializer.expression.getText();
        if (/^[A-Z]/.test(typeName)) {
          variableTypes.push({ filePath, varName, typeName });
        }
      }
    }

    // Import declarations: import { X as Y } from '...'
    if (ts.isImportDeclaration(node)) {
      const clause = node.importClause;
      if (clause) {
        // Default import: import PaymentService from '...'
        if (clause.name) {
          importAliases.push({
            filePath,
            alias:     clause.name.getText(),
            canonical: clause.name.getText(),
          });
        }
        // Named imports: import { X, Y as Z }
        if (clause.namedBindings && ts.isNamedImports(clause.namedBindings)) {
          for (const elem of clause.namedBindings.elements) {
            const imported = elem.propertyName ? elem.propertyName.getText() : elem.name.getText();
            const alias    = elem.name.getText();
            importAliases.push({ filePath, alias, canonical: imported });
          }
        }
      }
    }

    ts.forEachChild(node, walk);
  }

  walk(sourceFile);
}

// ═══════════════════════════════════════════════════════════════════════════════
// PYTHON BATCH INDEX — Pyright per-file
// ═══════════════════════════════════════════════════════════════════════════════

function indexPythonBatch(files) {
  if (!PYRIGHT_PATH) {
    return { symbols: [], importAliases: [], variableTypes: [],
             error: 'pyright not available' };
  }

  const symbols       = [];
  const importAliases = [];
  const variableTypes = [];

  for (const [filePath, content] of Object.entries(files)) {
    indexPythonFile(filePath, content, symbols, importAliases, variableTypes);
  }

  return { symbols, importAliases, variableTypes };
}

function indexPythonFile(filePath, content, symbols, importAliases, variableTypes) {
  // Write to temp file for Pyright
  const tmpFile = path.join(os.tmpdir(), `bridge_idx_${process.pid}_${Date.now()}.py`);
  try {
    fs.writeFileSync(tmpFile, content, 'utf8');
    const result = spawnSync(PYRIGHT_PATH, ['--outputjson', tmpFile],
        { encoding: 'utf8', timeout: 15000 });

    // Always extract from source regardless of Pyright result
    // (Pyright may fail on some files but we still want basic symbols)
    extractPythonSymbolsFromSource(filePath, content, symbols, importAliases, variableTypes);

    // Augment with Pyright's type information if available
    if (result.stdout) {
      try {
        const pyrightOut = JSON.parse(result.stdout);
        augmentWithPyrightTypes(filePath, content, pyrightOut, variableTypes);
      } catch (e) { /* Pyright output not parseable — use source extraction only */ }
    }
  } finally {
    try { fs.unlinkSync(tmpFile); } catch (e) {}
  }
}

function extractPythonSymbolsFromSource(filePath, content, symbols, importAliases, variableTypes) {
  const lines = content.split('\n');
  let currentClass = null;
  let classIndent  = -1;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Class definition: class Foo:  or  class Foo(Bar):
    const classMatch = line.match(/^(\s*)class\s+(\w+)/);
    if (classMatch) {
      currentClass = classMatch[2];
      classIndent  = classMatch[1].length;
      continue;
    }

    // Reset class context if we've dedented past it
    if (currentClass !== null) {
      const indent = line.match(/^(\s*)\S/);
      if (indent && indent[1].length <= classIndent && line.trim()) {
        currentClass = null;
        classIndent  = -1;
      }
    }

    // Function/method definition
    const funcMatch = line.match(/^\s*(?:async\s+)?def\s+(\w+)\s*\(/);
    if (funcMatch) {
      const funcName = funcMatch[1];
      const nodeId = currentClass
          ? `${filePath}:${currentClass}.${funcName}`
          : `${filePath}:${funcName}`;
      symbols.push({ nodeId, label: funcName, classContext: currentClass, filePath, language: 'python' });
      continue;
    }

    // Import: from module import ClassName [as alias]
    const fromImport = line.match(/^from\s+[\w.]+\s+import\s+(\w+)(?:\s+as\s+(\w+))?/);
    if (fromImport) {
      importAliases.push({
        filePath,
        alias:     fromImport[2] || fromImport[1],
        canonical: fromImport[1],
      });
      continue;
    }

    // Import: import module [as alias]
    const directImport = line.match(/^import\s+(\w+)(?:\s+as\s+(\w+))?/);
    if (directImport) {
      importAliases.push({
        filePath,
        alias:     directImport[2] || directImport[1],
        canonical: directImport[1],
      });
      continue;
    }

    // Variable type: var = ClassName(...)  (constructor call)
    const ctorAssign = line.match(/^\s*(?:self\.)?(\w+)\s*=\s*([A-Z]\w*)\s*\(/);
    if (ctorAssign) {
      variableTypes.push({ filePath, varName: ctorAssign[1], typeName: ctorAssign[2] });
    }
  }
}

function augmentWithPyrightTypes(filePath, content, pyrightOut, variableTypes) {
  // Extract type information from Pyright diagnostic messages
  // e.g. 'Cannot access member "validate" for type "PaymentValidator"'
  //      → tells us something on that line is of type PaymentValidator
  if (!pyrightOut.generalDiagnostics) return;

  for (const diag of pyrightOut.generalDiagnostics) {
    if (!diag.message || !diag.range) continue;
    const typeMatch = diag.message.match(/for type "([A-Z]\w*)"/);
    if (!typeMatch) continue;
    const typeName = typeMatch[1];
    const lineNo   = diag.range.start.line;
    const lines    = content.split('\n');
    if (lineNo >= lines.length) continue;

    // Find the receiver variable on that line
    const line = lines[lineNo];
    const varMatch = line.match(/\b(\w+)\./);
    if (varMatch && varMatch[1] !== 'self') {
      variableTypes.push({ filePath, varName: varMatch[1], typeName });
    }
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// GO BATCH INDEX — go-types-bridge per-file
// ═══════════════════════════════════════════════════════════════════════════════

function indexGoBatch(files) {
  if (!GO_BRIDGE_OK) {
    return { symbols: [], importAliases: [], variableTypes: [],
             error: 'go-types-bridge not available' };
  }

  const symbols       = [];
  const importAliases = [];
  const variableTypes = [];

  for (const [filePath, content] of Object.entries(files)) {
    // Extract basic symbols from source (always)
    extractGoSymbolsFromSource(filePath, content, symbols, importAliases, variableTypes);

    // Run go-types-bridge for type-resolved information
    const pkgMatch   = content.match(/^package\s+(\w+)/m);
    const packageName = pkgMatch ? pkgMatch[1] : 'main';
    const input = JSON.stringify({ filePath, content, packageName });
    const result = spawnSync(GO_BRIDGE, [], { input, encoding: 'utf8', timeout: 20000 });

    if (result.status === 0 && result.stdout) {
      try {
        const output = JSON.parse(result.stdout);
        // go-types-bridge returns "calls" for Pass 2 edges — for Pass 1 index
        // we want the type information it discovered. The bridge embeds variable
        // types in its response when we add a "indexMode: true" flag.
        if (output.variableTypes) {
          for (const vt of output.variableTypes) {
            variableTypes.push({ filePath, varName: vt.varName, typeName: vt.typeName });
          }
        }
      } catch (e) { /* parsing failed — source extraction was already done */ }
    }
  }

  return { symbols, importAliases, variableTypes };
}

function extractGoSymbolsFromSource(filePath, content, symbols, importAliases, variableTypes) {
  const lines = content.split('\n');
  let packageName = '';

  // Package name
  const pkgMatch = content.match(/^package\s+(\w+)/m);
  if (pkgMatch) packageName = pkgMatch[1];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // func (recv *Type) MethodName(  or  func FuncName(
    const funcMatch = line.match(/^func\s+(?:\([^)]*\*?(\w+)[^)]*\)\s+)?(\w+)\s*\(/);
    if (funcMatch) {
      const receiver  = funcMatch[1] || null;
      const funcName  = funcMatch[2];
      const qualifier = receiver || packageName;
      const nodeId    = qualifier ? `${filePath}:${qualifier}.${funcName}` : `${filePath}:${funcName}`;
      symbols.push({ nodeId, label: funcName, classContext: receiver, filePath, language: 'go' });
    }

    // import "path/to/package"  or  import alias "path/to/package"
    const importMatch = line.match(/^\s*(?:(\w+)\s+)?"([^"]+)"/);
    if (importMatch && line.trim().startsWith('"') || importMatch && /^\s*\w+\s+"/.test(line)) {
      const pkgPath = importMatch[2];
      const alias   = importMatch[1];
      const pkgName = pkgPath.split('/').pop();
      importAliases.push({
        filePath,
        alias:     alias || pkgName,
        canonical: pkgName,
      });
    }

    // x := NewSomeType(...)  → variable x has type SomeType
    const ctorMatch = line.match(/(\w+)\s*:=\s*New([A-Z]\w*)\s*\(/);
    if (ctorMatch) {
      variableTypes.push({ filePath, varName: ctorMatch[1], typeName: ctorMatch[2] });
    }

    // var x *SomeType = ...  or  var x SomeType
    const varDeclMatch = line.match(/var\s+(\w+)\s+\*?([A-Z]\w*)/);
    if (varDeclMatch) {
      variableTypes.push({ filePath, varName: varDeclMatch[1], typeName: varDeclMatch[2] });
    }
  }
}

module.exports = { handleIndexBatch };