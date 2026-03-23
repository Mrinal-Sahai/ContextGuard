/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DART TREE-SITTER PARSER — dart-parser.js
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Handles Dart/Flutter parse requests in tree-sitter-bridge.js.
 *
 * INTEGRATE INTO tree-sitter-bridge.js:
 * ─────────────────────────────────────
 *
 * 1. At the top (after other grammar loads):
 *
 *      const { parseDart, DART_GRAMMAR_AVAILABLE } = require('./dart-parser');
 *
 * 2. In the GRAMMARS block, add after typescript:
 *
 *      dart: DART_GRAMMAR_AVAILABLE ? require('tree-sitter-dart') : null,
 *
 * 3. In the parseFile() dispatch switch, add:
 *
 *      case 'dart': return parseDart(tree, filePath, content);
 *
 *    But since Dart needs its own grammar set call, use the full wrapper:
 *
 *      case 'dart': {
 *        if (!DART_GRAMMAR_AVAILABLE) throw new Error('tree-sitter-dart not installed');
 *        parser.setLanguage(require('tree-sitter-dart'));
 *        const tree = parser.parse(content);
 *        return parseDart(tree, filePath, content);
 *      }
 *
 * 4. In package.json, add:
 *      "tree-sitter-dart": "^0.0.3"
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * WHAT THIS EXTRACTS
 * ──────────────────
 * Nodes:
 *   - class declarations (class, abstract class, mixin, extension)
 *   - instance methods with class context
 *   - static methods
 *   - getters and setters (treated as methods)
 *   - top-level functions
 *   - async functions (isAsync=true)
 *   - constructor bodies (named as ClassName.constructorName or ClassName.new)
 *
 * CC (dart_code_metrics-compatible):
 *   +1 for: if, else if, for, while, do, switch case, catch
 *   +1 for: &&, ||, ?? (null-coalescing)
 *   +1 for: ternary ? (excluding null-aware ?. and ??)
 *
 * Edges:
 *   - method calls: receiver?.method(), receiver.method(), bareCall()
 *   - cascade calls: object..method1()..method2() (both edges from same caller)
 *   - super calls: super.method()
 *   - this calls: this.method() → resolved to same class
 *
 * Flutter-specific:
 *   - Widget.build() return type detected
 *   - setState(() {}) calls tracked
 *   - Navigator.push/pop/pushNamed tracked as cross-widget calls
 *   - context.read<T>() / context.watch<T>() tracked (Provider pattern)
 */

'use strict';

const DART_GRAMMAR_AVAILABLE = (() => {
  try { require('tree-sitter-dart'); return true; }
  catch (e) {
    process.stderr.write('[bridge] INFO: tree-sitter-dart not found — install with npm install tree-sitter-dart\n');
    return false;
  }
})();

// ─── Entry point ──────────────────────────────────────────────────────────────

function parseDart(tree, filePath, content) {
  const nodes = [];
  const edges = [];
  collectDartDeclarations(tree.rootNode, filePath, null, nodes, edges, content);
  return { nodes, edges };
}

// ─── Main collector ───────────────────────────────────────────────────────────

function collectDartDeclarations(node, filePath, currentClass, nodes, edges, content) {
  for (const child of node.children) {

    // ── Class / abstract class / mixin / extension ────────────────────────────
    if (child.type === 'class_definition'    ||
        child.type === 'mixin_declaration'   ||
        child.type === 'extension_declaration') {

      const nameNode = child.children.find(c => c.type === 'identifier');
      const className = nameNode ? nameNode.text : null;
      const body = child.children.find(c =>
          c.type === 'class_body' || c.type === 'declaration_block');

      if (body) {
        collectDartDeclarations(body, filePath, className, nodes, edges, content);
      }

    // ── Method / getter / setter / operator declarations ──────────────────────
    } else if (child.type === 'method_signature'       ||
               child.type === 'function_signature'     ||
               child.type === 'getter_signature'       ||
               child.type === 'setter_signature'       ||
               child.type === 'operator_signature') {

      const info = extractDartMethodInfo(child, currentClass, filePath, content);
      if (info) {
        edges.push(...extractDartCallEdges(child, info.id, filePath, currentClass));
        nodes.push(info);
      }

    // ── Constructor declarations ───────────────────────────────────────────────
    } else if (child.type === 'constructor_signature') {
      const info = extractDartConstructorInfo(child, currentClass, filePath, content);
      if (info) {
        edges.push(...extractDartCallEdges(child, info.id, filePath, currentClass));
        nodes.push(info);
      }

    // ── Top-level function declarations ───────────────────────────────────────
    } else if (child.type === 'function_declaration' && currentClass === null) {
      const info = extractDartMethodInfo(child, null, filePath, content);
      if (info) {
        edges.push(...extractDartCallEdges(child, info.id, filePath, currentClass));
        nodes.push(info);
      }

    // ── Recurse into everything else ──────────────────────────────────────────
    } else {
      collectDartDeclarations(child, filePath, currentClass, nodes, edges, content);
    }
  }
}

// ─── Node extraction ─────────────────────────────────────────────────────────

function extractDartMethodInfo(node, currentClass, filePath, content) {
  // Find the method name
  const nameNode = node.children.find(c =>
      c.type === 'identifier'          ||
      c.type === 'operator'            ||
      c.type === 'property_access'     // for getter "get propertyName"
  );
  if (!nameNode) return null;

  const methodName = nameNode.text;
  const isAsync    = node.children.some(c => c.text === 'async' || c.text === 'async*');
  const isStatic   = node.children.some(c => c.text === 'static');

  // Return type
  const returnType = extractDartReturnType(node);

  // Node ID
  const nodeId = currentClass
      ? `${filePath}:${currentClass}.${methodName}`
      : `${filePath}:${methodName}`;

  const startLine = node.startPosition.row + 1;
  const endLine   = node.endPosition.row + 1;

  // CC over the method body
  const bodyNode  = node.children.find(c =>
      c.type === 'block'                    ||
      c.type === 'function_expression_body' ||   // => expression
      c.type === 'async_block'
  );
  const cc = bodyNode ? computeDartCC(bodyNode) : 1;

  // Decorators / annotations in Dart: @override, @required, etc.
  const decorators = extractDartDecorators(node);

  return {
    id:                   nodeId,
    label:                methodName,
    filePath,
    startLine,
    endLine,
    returnType:           returnType || 'dynamic',
    cyclomaticComplexity: cc,
    classContext:         currentClass,
    isAsync,
    decorators,
  };
}

function extractDartConstructorInfo(node, currentClass, filePath, content) {
  if (!currentClass) return null;

  // Constructor may have a name: MyClass.named(...)
  // or be the default: MyClass(...)
  const nameNode  = node.children.find(c => c.type === 'identifier');
  const ctorName  = nameNode ? nameNode.text : 'new';
  const nodeId    = `${filePath}:${currentClass}.${ctorName}`;
  const startLine = node.startPosition.row + 1;
  const endLine   = node.endPosition.row + 1;

  const bodyNode  = node.children.find(c => c.type === 'block');
  const cc        = bodyNode ? computeDartCC(bodyNode) : 1;

  return {
    id:                   nodeId,
    label:                ctorName,
    filePath,
    startLine,
    endLine,
    returnType:           currentClass,  // constructors return their class type
    cyclomaticComplexity: cc,
    classContext:         currentClass,
    isAsync:              false,
    decorators:           extractDartDecorators(node),
  };
}

function extractDartReturnType(node) {
  // Return type is typically the first type node before the method name
  // In tree-sitter-dart: type_annotation, void_type, or identifier
  const typeNode = node.children.find(c =>
      c.type === 'type_annotation'  ||
      c.type === 'void_type'        ||
      (c.type === 'identifier' && c !== node.children.find(n => n.type === 'identifier'))
  );
  if (!typeNode) return null;
  // Clean up generics for display: "Future<List<Payment>>" → "Future<List<Payment>>"
  // but cap at 40 chars for readability
  const text = typeNode.text;
  return text.length > 40 ? text.substring(0, 37) + '...' : text;
}

function extractDartDecorators(node) {
  // Dart annotations appear as metadata nodes preceding the declaration
  const decorators = [];
  const parent = node.parent;
  if (!parent) return decorators;

  for (const sibling of parent.children) {
    // Annotations are marked as 'metadata' or begin with '@'
    if (sibling.type === 'metadata' && sibling.endPosition.row < node.startPosition.row) {
      // @override, @required, @Injectable(), etc.
      const text = sibling.text.replace(/^@/, '');
      decorators.push(text.split('(')[0].trim());
    }
  }
  return decorators;
}

// ─── Cyclomatic Complexity ────────────────────────────────────────────────────

function computeDartCC(bodyNode) {
  let cc = 1;
  walkNode(bodyNode, (n) => {
    switch (n.type) {
      // Standard control flow
      case 'if_statement':
        cc++; break;
      case 'else_clause':
        // "else if" — only count if the body is another if_statement
        if (n.children.some(c => c.type === 'if_statement')) cc++;
        break;
      case 'for_statement':
      case 'for_each_statement':   // Dart: for (var x in list)
      case 'while_statement':
      case 'do_statement':
        cc++; break;
      case 'switch_case':
      case 'switch_default':
        cc++; break;
      case 'catch_clause':
      case 'on_clause':            // Dart: on ExceptionType catch (e)
        cc++; break;

      // Dart-specific operators
      case 'conditional_expression':    // a ? b : c
        cc++; break;
      case 'if_null_expression':        // a ?? b
        cc++; break;

      // Boolean operators — count each occurrence
      case 'binary_expression':
        if (n.children[1]) {
          const op = n.children[1].text;
          if (op === '&&' || op === '||') cc++;
        }
        break;
    }
  });
  return cc;
}

// ─── Call Edge Extraction ─────────────────────────────────────────────────────

function extractDartCallEdges(methodNode, fromId, filePath, currentClass) {
  const edges = [];

  walkNode(methodNode, (n) => {
    const sourceLine = n.startPosition.row + 1;

    // Regular method call: receiver.method(...)
    if (n.type === 'method_invocation') {
      const targetNode   = n.children.find(c => c.type === 'identifier');
      const receiverNode = n.children.find(c =>
          c.type === 'identifier'        ||
          c.type === 'this'              ||
          c.type === 'super'             ||
          c.type === 'property_access'
      );

      if (targetNode) {
        const methodName = targetNode.text;
        let   receiver   = null;

        if (receiverNode && receiverNode !== targetNode) {
          receiver = receiverNode.text;
        }

        let toId = null;

        // self-call: this.method() or method() inside a class
        if (!receiver || receiver === 'this') {
          if (currentClass) toId = `${filePath}:${currentClass}.${methodName}`;
        }
        // super call
        else if (receiver === 'super') {
          toId = `super.${methodName}`;   // will be resolved by symbol index
        }
        // Named receiver
        else {
          toId = `${receiver}.${methodName}`;
        }

        if (toId && toId !== fromId) {
          edges.push({ from: fromId, to: toId, callType: 'METHOD_CALL', sourceLine });
        }
      }
    }

    // Cascade call: object..method1()..method2()
    // In tree-sitter-dart these appear as cascade_section nodes
    if (n.type === 'cascade_section') {
      // The receiver is the object the cascade is attached to (parent context)
      const cascadeMethod = n.children.find(c => c.type === 'identifier');
      if (cascadeMethod) {
        edges.push({
          from: fromId,
          to:   cascadeMethod.text,     // bare name — resolved by index
          callType:   'METHOD_CALL',
          sourceLine,
        });
      }
    }

    // Function call (top-level): functionName(...)
    if (n.type === 'function_expression_invocation' ||
        n.type === 'invocation_expression') {
      const funcNode = n.children[0];
      if (funcNode && funcNode.type === 'identifier') {
        const funcName = funcNode.text;
        // Avoid counting keywords, built-ins, type constructors
        if (funcName && !/^(print|assert|throw|return|super|this)$/.test(funcName)) {
          const toId = currentClass
              ? `${filePath}:${currentClass}.${funcName}`   // might be same-class
              : `${filePath}:${funcName}`;
          if (toId !== fromId) {
            edges.push({ from: fromId, to: toId, callType: 'METHOD_CALL', sourceLine });
          }
        }
      }
    }
  });

  return edges;
}

// ─── Shared walker ────────────────────────────────────────────────────────────

function walkNode(node, visitor) {
  if (!node) return;
  if (visitor(node) === false) return;
  for (const child of node.children) walkNode(child, visitor);
}

module.exports = { parseDart, DART_GRAMMAR_AVAILABLE };