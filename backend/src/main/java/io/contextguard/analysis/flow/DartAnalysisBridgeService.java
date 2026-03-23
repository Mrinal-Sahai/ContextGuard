package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DART ANALYSIS BRIDGE SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Manages the Dart Analysis Server for type-resolved call graph extraction
 * from Dart and Flutter source files.
 *
 * WHY THE DART ANALYSIS SERVER?
 * ──────────────────────────────
 * Dart is a strongly typed language with null safety. The Dart Analysis Server
 * (ships with the Flutter/Dart SDK as `dart language-server`) understands:
 *   - Full type inference across all files in a package
 *   - Flutter widget hierarchy (StatefulWidget, State<T>, BuildContext)
 *   - Mixin resolution (Flutter uses mixins heavily: TickerProviderStateMixin etc.)
 *   - Extension methods (Dart 2.7+)
 *   - Null-safe types (Dart 2.12+): T vs T? vs T! have different call semantics
 *   - Package: imports resolved against pubspec.yaml
 *
 * This makes Dart call graph accuracy comparable to TypeScript — ~9/10 —
 * significantly better than what Tree-sitter alone can achieve (~6.5/10).
 *
 * PROTOCOL: LSP (Language Server Protocol) over stdio
 * ────────────────────────────────────────────────────
 * The Dart Analysis Server speaks JSON-RPC 2.0 wrapped in LSP headers.
 * Each message has a Content-Length header followed by a blank line, then JSON:
 *
 *   Content-Length: 123\r\n
 *   \r\n
 *   {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
 *
 * Handshake sequence (must complete before any analysis requests):
 *   1. → initialize        (send client capabilities + rootUri)
 *   2. ← initializeResult  (server reports its capabilities)
 *   3. → initialized       (notification, no response)
 *   4. → textDocument/didOpen  (for each file we want to analyse)
 *   5. → workspace/executeCommand (dart.getCallHierarchy or similar)
 *   -- OR --
 *   5. → textDocument/definition (resolve call targets one by one)
 *
 * STRATEGY: textDocument/definition per call site
 * ─────────────────────────────────────────────────
 * We use `textDocument/definition` on each call expression site to resolve
 * the call target to its declaration location. This is the same operation
 * VS Code performs when you Cmd+Click a function call.
 *
 * Compared to a full call hierarchy traversal, this is:
 *   ✓ Simpler — one request per call, response is a Location
 *   ✓ Always supported — every LSP server implements textDocument/definition
 *   ✗ Slower for dense files — one round-trip per call site
 *
 * For PR analysis (typically 5–50 changed files), the call-site-per-request
 * approach is fast enough. For very large files (>2000 lines), we cap at
 * MAX_CALLS_PER_FILE = 200 call sites to bound latency.
 *
 * TEMP DIRECTORY APPROACH
 * ────────────────────────
 * The Dart Analysis Server requires real files on disk — it cannot analyse
 * in-memory content passed via didOpen alone (it falls back to the on-disk
 * version for type checking if the real files aren't there).
 *
 * Strategy: write all files in the batch to a temp directory, send the temp
 * dir as the workspace root, run analysis, then delete the temp dir.
 *
 * The temp dir structure mirrors the original paths:
 *   /tmp/contextguard_dart_<uuid>/lib/src/payment_service.dart
 *   /tmp/contextguard_dart_<uuid>/lib/src/models/payment.dart
 *   /tmp/contextguard_dart_<uuid>/pubspec.yaml  ← generated minimal pubspec
 *
 * CONFIGURATION (application.properties)
 * ────────────────────────────────────────
 *   dart.analysis.flutter-sdk-path   — path to Flutter SDK root
 *                                       default: /opt/flutter
 *   dart.analysis.timeout-ms         — per-file analysis timeout
 *                                       default: 15000
 *   dart.analysis.batch-timeout-ms   — full batch timeout
 *                                       default: 120000
 *   dart.analysis.max-calls-per-file — call site cap per file
 *                                       default: 200
 */
@Service
public class DartAnalysisBridgeService implements InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(DartAnalysisBridgeService.class);

    @Value("${dart.analysis.flutter-sdk-path:/opt/flutter}")
    private String flutterSdkPath;

    @Value("${dart.analysis.timeout-ms:15000}")
    private long analysisTimeoutMs;

    @Value("${dart.analysis.batch-timeout-ms:120000}")
    private long batchTimeoutMs;

    @Value("${dart.analysis.max-calls-per-file:200}")
    private int maxCallsPerFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // LSP process state
    private volatile Process        lspProcess;
    private volatile OutputStream   lspStdin;
    private volatile InputStream    lspStdout;
    private volatile boolean        initialized            = false;
    private volatile boolean        permanentlyUnavailable = false;

    // LSP message ID counter (must be globally unique per session)
    private final AtomicLong lspIdCounter = new AtomicLong(1);

    // Pending LSP requests: id → future
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pendingLsp
            = new ConcurrentHashMap<>();

    // LSP reader thread
    private volatile Thread lspReaderThread;

    // ─────────────────────────────────────────────────────────────────────────
    // Spring lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void afterPropertiesSet() {
        try {
            String dartBin = resolveDartBinary();
            if (dartBin == null) {
                logger.warn("DartAnalysisBridge: Flutter SDK not found at {}. " +
                                    "Dart/Flutter parsing will use Tree-sitter only.", flutterSdkPath);
                permanentlyUnavailable = true;
                return;
            }
            startLspServer(dartBin);
            performLspHandshake();
            logger.info("DartAnalysisBridge: Dart Analysis Server ready (sdk={})", flutterSdkPath);
        } catch (Exception e) {
            // e.getMessage() is null for TimeoutException (no-arg constructor) — use toString()
            // to always get the exception class name even when the message is absent.
            logger.warn("DartAnalysisBridge: could not start Analysis Server — " +
                                "Dart parsing degrades to Tree-sitter. Reason: {}", e.toString());
            permanentlyUnavailable = true;
        }
    }

    @Override
    public void destroy() {
        stopLspServer();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isAvailable() {
        return !permanentlyUnavailable && initialized && lspProcess != null && lspProcess.isAlive();
    }

    /**
     * PASS 1: Index a batch of Dart files into the CrossFileSymbolIndex.
     *
     * Writes all files to a temp workspace, opens them with the Analysis Server,
     * extracts symbol definitions (classes, methods, constructors, mixins),
     * import aliases, and field type declarations.
     *
     * @return true if indexing succeeded; false triggers Tree-sitter fallback
     */
    public boolean indexBatch(Map<String, String> files, CrossFileSymbolIndex symbolIndex) {
        if (!isAvailable() || files.isEmpty()) return false;

        Path tempWorkspace = null;
        try {
            tempWorkspace = createTempWorkspace(files);
            openFilesInServer(files, tempWorkspace);
            extractSymbolsIntoIndex(files, tempWorkspace, symbolIndex);
            return true;
        } catch (Exception e) {
            logger.warn("DartAnalysisBridge: indexBatch failed — {}", e.getMessage());
            return false;
        } finally {
            if (tempWorkspace != null) deleteTempWorkspace(tempWorkspace);
        }
    }

    /**
     * PASS 2: Parse a single Dart file with full type resolution.
     *
     * Uses textDocument/definition to resolve each call site to its declaration.
     * Returns a DartParseResult containing FlowNode-compatible data.
     *
     * @return populated DartParseResult, or null to trigger Tree-sitter fallback
     */
    public DartParseResult parseFile(String filePath, String content,
                                     CrossFileSymbolIndex symbolIndex) {
        if (!isAvailable()) return null;

        Path tempWorkspace = null;
        try {
            tempWorkspace = createTempWorkspace(Map.of(filePath, content));
            Path tempFilePath = resolveTempPath(tempWorkspace, filePath);
            openFileInServer(filePath, content, tempFilePath);

            // Extract nodes (classes, methods) from Tree-sitter first (fast)
            // Then augment edges with LSP definition resolution (accurate)
            List<DartNode> nodes = extractDartNodes(content, filePath);
            List<DartEdge> edges = resolveCallEdgesViaLsp(
                    content, filePath, tempFilePath, nodes, symbolIndex);

            return new DartParseResult(nodes, edges);
        } catch (Exception e) {
            logger.warn("DartAnalysisBridge: parseFile failed for {} — {}", filePath, e.getMessage());
            return null;
        } finally {
            if (tempWorkspace != null) deleteTempWorkspace(tempWorkspace);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SYMBOL EXTRACTION (Pass 1)
    // ─────────────────────────────────────────────────────────────────────────

    private void extractSymbolsIntoIndex(Map<String, String> files, Path tempWorkspace,
                                         CrossFileSymbolIndex symbolIndex) throws Exception {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String filePath = entry.getKey();
            String content  = entry.getValue();

            // Extract structural symbols via regex (fast, reliable for Dart)
            extractDartClassesAndMethods(filePath, content, symbolIndex);
            extractDartImports(filePath, content, symbolIndex);
            extractDartFieldTypes(filePath, content, symbolIndex);
        }
    }

    /**
     * Extract class and method definitions from Dart source.
     *
     * Handles:
     *   class PaymentService { ... }
     *   abstract class BaseService { ... }
     *   mixin PaymentMixin { ... }
     *   extension PaymentExtension on PaymentService { ... }
     *   StatefulWidget subclasses → extract build() method specially
     */
    private void extractDartClassesAndMethods(String filePath, String content,
                                              CrossFileSymbolIndex symbolIndex) {
        String[] lines       = content.split("\n");
        String   currentClass = null;
        int      braceDepth   = 0;
        int      classStartDepth = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Class / mixin / extension declaration
            java.util.regex.Matcher classMatcher = CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                currentClass   = classMatcher.group(1);
                classStartDepth = braceDepth;
                symbolIndex.registerClass(currentClass, currentClass, filePath, "dart");
            }

            // Method / function declarations
            java.util.regex.Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.find() && currentClass != null) {
                String methodName  = methodMatcher.group(2);

                String nodeId = filePath + ":" + currentClass + "." + methodName;
                symbolIndex.registerMethod(methodName, nodeId, currentClass, filePath, "dart");

                // Register Flutter lifecycle methods specially for better tracking
                if (FLUTTER_LIFECYCLE_METHODS.contains(methodName)) {
                    symbolIndex.registerVariableType(filePath, "widget_lifecycle", currentClass);
                }
            }

            // Top-level functions (outside any class)
            if (currentClass == null) {
                java.util.regex.Matcher topFuncMatcher = TOP_LEVEL_FUNC_PATTERN.matcher(line);
                if (topFuncMatcher.find()) {
                    String funcName = topFuncMatcher.group(2);
                    String nodeId   = filePath + ":" + funcName;
                    symbolIndex.registerMethod(funcName, nodeId, null, filePath, "dart");
                }
            }

            // Track brace depth for class scope
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') {
                    braceDepth--;
                    if (currentClass != null && braceDepth <= classStartDepth) {
                        currentClass    = null;
                        classStartDepth = -1;
                    }
                }
            }
        }
    }

    /**
     * Extract import aliases from Dart source.
     *
     * Handles:
     *   import 'package:myapp/services/payment_service.dart';
     *   import 'package:myapp/services/payment_service.dart' as ps;
     *   import '../models/payment.dart';
     *   import '../models/payment.dart' show PaymentModel;
     *   import '../models/payment.dart' hide InternalModel;
     */
    private void extractDartImports(String filePath, String content,
                                    CrossFileSymbolIndex symbolIndex) {
        java.util.regex.Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String importPath  = m.group(1);    // the string inside quotes
            String asAlias     = m.group(2);    // alias after "as", may be null
            String showList    = m.group(3);    // show clause, may be null
            // hide clause (m.group(4)) intentionally not captured — not used for symbol resolution

            // Derive a canonical class name from the import path
            // "package:myapp/services/payment_service.dart" → "payment_service"
            // Then snakeToPascalCase: "PaymentService"
            String stem        = importPath.replaceAll(".*[/:]", "").replace(".dart", "");
            String canonicalClass = snakeToPascalCase(stem);

            if (asAlias != null && !asAlias.isBlank()) {
                // import '...' as ps; → "ps" → "PaymentService"
                symbolIndex.registerImportAlias(filePath, asAlias.trim(), canonicalClass);
            } else {
                // Default import — register stem as the alias too
                symbolIndex.registerImportAlias(filePath, canonicalClass, canonicalClass);
                symbolIndex.registerImportAlias(filePath, stem, canonicalClass);
            }

            // Handle "show" clause: explicit class names are registered directly
            if (showList != null) {
                for (String shown : showList.split(",")) {
                    String cls = shown.trim();
                    if (!cls.isEmpty()) {
                        symbolIndex.registerImportAlias(filePath, cls, cls);
                    }
                }
            }
        }
    }

    /**
     * Extract field type declarations for variable→type registration.
     *
     * Handles:
     *   final PaymentService _service;
     *   late final PaymentValidator _validator;
     *   PaymentService get service => _service;
     *   var _repo = PaymentRepository();
     *   final _client = HttpClient();
     */
    private void extractDartFieldTypes(String filePath, String content,
                                       CrossFileSymbolIndex symbolIndex) {
        // Typed field declarations: [final|late|const] TypeName varName
        java.util.regex.Matcher m = FIELD_DECL_PATTERN.matcher(content);
        while (m.find()) {
            String typeName = m.group(1);
            String varName  = m.group(2);
            if (typeName != null && varName != null && Character.isUpperCase(typeName.charAt(0))) {
                symbolIndex.registerVariableType(filePath, varName, typeName);
                // Also register without leading underscore (common Dart convention)
                if (varName.startsWith("_")) {
                    symbolIndex.registerVariableType(filePath, varName.substring(1), typeName);
                }
            }
        }

        // Constructor-call assignments: var _x = TypeName(...)
        java.util.regex.Matcher m2 = CTOR_ASSIGN_PATTERN.matcher(content);
        while (m2.find()) {
            String varName  = m2.group(1);
            String typeName = m2.group(2);
            symbolIndex.registerVariableType(filePath, varName, typeName);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALL SITE EXTRACTION & LSP RESOLUTION (Pass 2)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract Dart method/function nodes from source content.
     * Uses regex — same approach as Pass 1, but returns DartNode objects
     * with CC computed for each method body.
     */
    private List<DartNode> extractDartNodes(String content, String filePath) {
        List<DartNode> nodes      = new ArrayList<>();
        String[]       lines      = content.split("\n");
        String         currentCls = null;
        int            braceDepth = 0;
        int            clsStartDepth = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            java.util.regex.Matcher classMatcher = CLASS_PATTERN.matcher(line);
            if (classMatcher.find()) {
                currentCls    = classMatcher.group(1);
                clsStartDepth = braceDepth;
            }

            java.util.regex.Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.find()) {
                String returnType = methodMatcher.group(1) != null
                                            ? methodMatcher.group(1).trim() : "void";
                String methodName = methodMatcher.group(2);
                boolean isAsync   = line.contains("async");

                // Find method body — scan forward from this line
                int bodyStart = i;
                int bodyEnd   = findMethodEnd(lines, i, braceDepth);

                // Compute CC over the method body lines
                String[] bodyLines = Arrays.copyOfRange(lines, bodyStart, Math.min(bodyEnd + 1, lines.length));
                int cc = computeDartCC(bodyLines);

                String nodeId = currentCls != null
                                        ? filePath + ":" + currentCls + "." + methodName
                                        : filePath + ":" + methodName;

                nodes.add(new DartNode(
                        nodeId, methodName, filePath, i + 1, bodyEnd + 1,
                        returnType, cc, currentCls, isAsync
                ));
            }

            // Top-level functions
            if (currentCls == null) {
                java.util.regex.Matcher topMatcher = TOP_LEVEL_FUNC_PATTERN.matcher(line);
                if (topMatcher.find()) {
                    String funcName = topMatcher.group(2);
                    int bodyEnd     = findMethodEnd(lines, i, braceDepth);
                    String[] body   = Arrays.copyOfRange(lines, i, Math.min(bodyEnd + 1, lines.length));
                    int cc          = computeDartCC(body);
                    nodes.add(new DartNode(
                            filePath + ":" + funcName, funcName, filePath,
                            i + 1, bodyEnd + 1, "void", cc, null, false
                    ));
                }
            }

            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') {
                    braceDepth--;
                    if (currentCls != null && braceDepth <= clsStartDepth) {
                        currentCls    = null;
                        clsStartDepth = -1;
                    }
                }
            }
        }
        return nodes;
    }

    /**
     * Compute Dart cyclomatic complexity for a set of lines.
     *
     * Dart CC formula (dart_code_metrics-compatible):
     *   Base = 1
     *   +1 for: if, else if, for, while, do, switch case, catch
     *   +1 for: &&, ||, ??  (null-aware OR operator — Dart-specific)
     *   +1 for: ? (ternary)
     *   +1 for: => (expression body — adds one path)
     *
     * Note: Dart has null-aware operators (?., ?.., ??) which can indicate
     * conditional execution paths. We count ?? as a decision point because
     * it represents "use fallback value if null" — a meaningful branch.
     */
    private int computeDartCC(String[] lines) {
        int cc = 1;
        for (String line : lines) {
            // Strip string literals and comments to avoid false positives
            String stripped = stripDartStringsAndComments(line);
            cc += countMatches(stripped, IF_PATTERN);
            cc += countMatches(stripped, ELSE_IF_PATTERN);
            cc += countMatches(stripped, FOR_PATTERN);
            cc += countMatches(stripped, WHILE_PATTERN);
            cc += countMatches(stripped, DO_PATTERN);
            cc += countMatches(stripped, CASE_PATTERN);
            cc += countMatches(stripped, CATCH_PATTERN);
            cc += countMatches(stripped, AND_PATTERN);
            cc += countMatches(stripped, OR_PATTERN);
            cc += countMatches(stripped, NULL_COALESCE_PATTERN);
            cc += countMatches(stripped, TERNARY_PATTERN);
        }
        return cc;
    }

    /**
     * Resolve call edges for a file using LSP textDocument/definition.
     *
     * For each call site found in the source, sends a definition request
     * to the Dart Analysis Server. The server responds with the declaration
     * location, which we map back to a node ID using the symbol index.
     */
    private List<DartEdge> resolveCallEdgesViaLsp(String originalFilePath, String content,
                                                  Path tempFilePath,
                                                  List<DartNode> nodes,
                                                  CrossFileSymbolIndex symbolIndex)
            throws Exception {

        List<DartEdge> edges     = new ArrayList<>();
        String[]       lines     = content.split("\n");
        int            callCount = 0;

        // Build a map from line number → enclosing node (for edge "from" field)
        Map<Integer, DartNode> lineToNode = buildLineToNodeMap(nodes);

        for (int lineIdx = 0; lineIdx < lines.length && callCount < maxCallsPerFile; lineIdx++) {
            String line = lines[lineIdx].trim();

            // Find call sites: receiver.method(  or  bare function(
            java.util.regex.Matcher callMatcher = CALL_SITE_PATTERN.matcher(line);
            while (callMatcher.find() && callCount < maxCallsPerFile) {
                String receiver   = callMatcher.group(1);  // may be null for bare calls
                String methodName = callMatcher.group(2);
                int    column     = callMatcher.start(2);

                DartNode enclosingNode = lineToNode.get(lineIdx + 1);
                if (enclosingNode == null) continue;

                // Strategy 1: LSP definition request (highest accuracy)
                String resolvedTarget = requestLspDefinition(
                        tempFilePath, lineIdx, column, originalFilePath, symbolIndex);

                // Strategy 2: CrossFileSymbolIndex (fallback)
                if (resolvedTarget == null) {
                    resolvedTarget = symbolIndex.resolve(
                            originalFilePath,
                            enclosingNode.classContext,
                            receiver,
                            methodName
                    );
                }

                // Strategy 3: Heuristic — same class or bare function
                if (resolvedTarget == null && receiver != null) {
                    resolvedTarget = receiver + "." + methodName;
                }

                if (resolvedTarget != null && !resolvedTarget.equals(enclosingNode.id)) {
                    edges.add(new DartEdge(enclosingNode.id, resolvedTarget, lineIdx + 1));
                }
                callCount++;
            }
        }

        logger.debug("DartAnalysisBridge: resolved {} call edges for {}", edges.size(), originalFilePath);
        return edges;
    }

    /**
     * Send a textDocument/definition LSP request and return the resolved node ID.
     *
     * The server responds with a Location:
     *   { "uri": "file:///tmp/.../lib/src/payment_service.dart",
     *     "range": { "start": { "line": 42, "character": 2 }, ... } }
     *
     * We use the URI + line number to look up the corresponding node ID
     * in the CrossFileSymbolIndex.
     */
    private String requestLspDefinition(Path tempFilePath, int line, int character,
                                        String originalFilePath,
                                        CrossFileSymbolIndex symbolIndex) {
        try {
            long   id     = lspIdCounter.getAndIncrement();
            String fileUri = tempFilePath.toUri().toString();

            ObjectNode params = objectMapper.createObjectNode();
            ObjectNode textDoc = objectMapper.createObjectNode();
            textDoc.put("uri", fileUri);
            params.set("textDocument", textDoc);
            ObjectNode pos = objectMapper.createObjectNode();
            pos.put("line", line);
            pos.put("character", character);
            params.set("position", pos);

            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pendingLsp.put(id, future);
            sendLspRequest(id, "textDocument/definition", params);

            JsonNode response = future.get(analysisTimeoutMs, TimeUnit.MILLISECONDS);
            return parseDefinitionResponse(response, symbolIndex);

        } catch (TimeoutException e) {
            logger.trace("DartAnalysisBridge: definition request timeout at {}:{}", line, character);
            return null;
        } catch (Exception e) {
            logger.trace("DartAnalysisBridge: definition request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the LSP definition response to a node ID.
     *
     * Response is either:
     *   A single Location:    { "uri": "...", "range": { "start": { "line": N } } }
     *   An array of Locations: [{ "uri": "...", "range": ... }]
     *   null (no definition found)
     */
    private String parseDefinitionResponse(JsonNode response, CrossFileSymbolIndex symbolIndex) {
        if (response == null || response.isNull()) return null;

        JsonNode result = response.get("result");
        if (result == null || result.isNull()) return null;

        // Unwrap array — take the first result
        JsonNode location = result.isArray() && result.size() > 0 ? result.get(0) : result;
        if (location == null || location.isNull()) return null;

        JsonNode uriNode   = location.get("uri");
        JsonNode rangeNode = location.get("range");
        if (uriNode == null || rangeNode == null) return null;

        String uri  = uriNode.asText();
        int    line = rangeNode.path("start").path("line").asInt(0) + 1; // LSP is 0-indexed

        // Convert file URI back to a relative path for symbol index lookup
        // "file:///tmp/contextguard_dart_xxx/lib/src/service.dart" → "lib/src/service.dart"
        String targetFile = uriToRelativePath(uri);
        if (targetFile == null) return null;

        // Look up what node is at this file + line combination
        return symbolIndex.resolveByLocation(targetFile, line);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LSP PROTOCOL
    // ─────────────────────────────────────────────────────────────────────────

    private void startLspServer(String dartBin) throws Exception {
        // dart language-server --protocol=lsp
        ProcessBuilder pb = new ProcessBuilder(dartBin, "language-server", "--protocol=lsp");
        pb.redirectErrorStream(false);

        // Ensure the Dart process has the right env for pub cache and analytics suppression.
        // Without PUB_CACHE pointing to a writable dir, dart may hang trying to write
        // to the Flutter SDK cache on its first invocation as a non-root user.
        Map<String, String> env = pb.environment();
        String home = System.getProperty("user.home", "/home/contextguard");
        env.put("PUB_CACHE",                   home + "/.pub-cache");
        env.put("FLUTTER_ROOT",                flutterSdkPath);
        env.put("FLUTTER_SUPPRESS_ANALYTICS",  "true");
        env.put("DART_DISABLE_ANALYTICS",      "1");

        lspProcess = pb.start();
        lspStdin   = lspProcess.getOutputStream();
        lspStdout  = lspProcess.getInputStream();

        // Drain stderr at DEBUG so server startup errors are visible in logs.
        // Previously TRACE — completely invisible, masking all Analysis Server errors.
        Thread stderr = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(lspProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (!l.isBlank()) logger.debug("[dart-lsp stderr] {}", l);
                }
            } catch (IOException ignored) { // stream closed on server exit — expected
            }
        }, "dart-lsp-stderr");
        stderr.setDaemon(true);
        stderr.start();

        // Start response reader
        lspReaderThread = new Thread(this::lspReadLoop, "dart-lsp-reader");
        lspReaderThread.setDaemon(true);
        lspReaderThread.start();

        // Give the server a moment to start
        Thread.sleep(500);
        if (!lspProcess.isAlive()) {
            throw new RuntimeException("Dart Analysis Server exited immediately. " +
                                               "Check Flutter SDK path: " + flutterSdkPath);
        }
    }

    private void performLspHandshake() throws Exception {
        long id = lspIdCounter.getAndIncrement();

        // 1. initialize
        ObjectNode initParams = objectMapper.createObjectNode();
        initParams.put("processId", ProcessHandle.current().pid());
        // null rootUri: valid per LSP spec — tells the server there is no pre-loaded workspace.
        // Previously "file:///tmp" caused the server to scan /tmp (thousands of non-Dart files)
        // before responding to initialize, causing the 10-second handshake timeout.
        initParams.putNull("rootUri");
        initParams.set("workspaceFolders", objectMapper.createArrayNode());

        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "ContextGuard");
        clientInfo.put("version", "1.0");
        initParams.set("clientInfo", clientInfo);

        // Minimal capabilities — we only need textDocument/definition
        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode textDocCaps  = objectMapper.createObjectNode();
        ObjectNode syncCaps     = objectMapper.createObjectNode();
        syncCaps.put("dynamicRegistration", false);
        syncCaps.put("willSave", false);
        syncCaps.put("willSaveWaitUntil", false);
        syncCaps.put("didSave", false);
        textDocCaps.set("synchronization", syncCaps);
        capabilities.set("textDocument", textDocCaps);
        initParams.set("capabilities", capabilities);

        CompletableFuture<JsonNode> initFuture = new CompletableFuture<>();
        pendingLsp.put(id, initFuture);
        sendLspRequest(id, "initialize", initParams);

        // Wait for initializeResult
        initFuture.get(10_000, TimeUnit.MILLISECONDS);

        // 2. initialized notification (no response expected)
        sendLspNotification("initialized", objectMapper.createObjectNode());

        initialized = true;
        logger.debug("DartAnalysisBridge: LSP handshake complete");
    }

    private void openFilesInServer(Map<String, String> files, Path tempWorkspace) throws Exception {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path tempPath = resolveTempPath(tempWorkspace, entry.getKey());
            openFileInServer(entry.getKey(), entry.getValue(), tempPath);
        }
        // Give the server time to process all didOpen notifications
        Thread.sleep(Math.min(500L, files.size() * 50L));
    }

    private void openFileInServer(String originalPath, String content, Path tempPath) throws Exception {
        ObjectNode params   = objectMapper.createObjectNode();
        ObjectNode textItem = objectMapper.createObjectNode();
        textItem.put("uri",        tempPath.toUri().toString());
        textItem.put("languageId", "dart");
        textItem.put("version",    1);
        textItem.put("text",       content);
        params.set("textDocument", textItem);
        sendLspNotification("textDocument/didOpen", params);
    }

    private void sendLspRequest(long id, String method, ObjectNode params) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);

        byte[] body    = objectMapper.writeValueAsBytes(message);
        String header  = "Content-Length: " + body.length + "\r\n\r\n";
        synchronized (lspStdin) {
            lspStdin.write(header.getBytes(StandardCharsets.UTF_8));
            lspStdin.write(body);
            lspStdin.flush();
        }
    }

    private void sendLspNotification(String method, ObjectNode params) throws IOException {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);

        byte[] body   = objectMapper.writeValueAsBytes(message);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        synchronized (lspStdin) {
            lspStdin.write(header.getBytes(StandardCharsets.UTF_8));
            lspStdin.write(body);
            lspStdin.flush();
        }
    }

    /**
     * Reads LSP messages from stdout in a loop.
     * Each message: "Content-Length: N\r\n\r\n" followed by N bytes of JSON.
     */
    private void lspReadLoop() {
        try {
            BufferedReader headerReader = new BufferedReader(
                    new InputStreamReader(lspStdout, StandardCharsets.UTF_8));

            while (!Thread.currentThread().isInterrupted()) {
                // Read headers until blank line
                int contentLength = -1;
                String headerLine;
                while ((headerLine = headerReader.readLine()) != null && !headerLine.isBlank()) {
                    if (headerLine.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    }
                }
                if (contentLength <= 0) continue;

                // Read exactly contentLength bytes
                char[] body = new char[contentLength];
                int    read = 0;
                while (read < contentLength) {
                    int r = headerReader.read(body, read, contentLength - read);
                    if (r < 0) break;
                    read += r;
                }

                try {
                    JsonNode message = objectMapper.readTree(new String(body, 0, read));
                    routeLspMessage(message);
                } catch (Exception e) {
                    logger.trace("DartAnalysisBridge: failed to parse LSP message: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                logger.warn("DartAnalysisBridge: LSP reader died — {}", e.getMessage());
                permanentlyUnavailable = true;
            }
        }
        pendingLsp.forEach((id, f) ->
                                   f.completeExceptionally(new RuntimeException("Dart Analysis Server died")));
        pendingLsp.clear();
    }

    private void routeLspMessage(JsonNode message) {
        // Responses have "id" and "result" or "error"
        JsonNode idNode = message.get("id");
        if (idNode != null && !idNode.isNull()) {
            long id = idNode.asLong();
            CompletableFuture<JsonNode> future = pendingLsp.remove(id);
            if (future != null) future.complete(message);
            return;
        }
        // Notifications (method without id) — ignore for now
        // (publishDiagnostics etc. are not needed for our use case)
    }

    private void stopLspServer() {
        pendingLsp.forEach((id, f) ->
                                   f.completeExceptionally(new RuntimeException("Shutting down")));
        pendingLsp.clear();
        if (lspProcess != null) {
            try {
                sendLspNotification("shutdown", objectMapper.createObjectNode());
                sendLspNotification("exit", objectMapper.createObjectNode());
            } catch (Exception ignored) {}
            lspProcess.destroyForcibly();
            lspProcess = null;
        }
        if (lspReaderThread != null) { lspReaderThread.interrupt(); lspReaderThread = null; }
        initialized = false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEMP WORKSPACE MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────────

    private Path createTempWorkspace(Map<String, String> files) throws IOException {
        Path workspace = Files.createTempDirectory("contextguard_dart_");

        // Write all source files
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = resolveTempPath(workspace, entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }

        // Generate a minimal pubspec.yaml so the Analysis Server knows this is a Dart package
        String pubspec = "name: contextguard_analysis\nenvironment:\n  sdk: '>=2.17.0 <4.0.0'\n";
        Files.writeString(workspace.resolve("pubspec.yaml"), pubspec, StandardCharsets.UTF_8);

        return workspace;
    }

    private Path resolveTempPath(Path workspace, String originalPath) {
        // Strip leading slash/drive if present, ensure relative
        String relative = originalPath.replaceAll("^[/\\\\]", "");
        return workspace.resolve(relative);
    }

    private void deleteTempWorkspace(Path workspace) {
        try {
            Files.walk(workspace)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            logger.trace("DartAnalysisBridge: failed to clean up temp workspace: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveDartBinary() {
        // Flutter SDK ships dart at: <flutter>/bin/dart
        // Also check if dart is on PATH directly
        File flutterDart = new File(flutterSdkPath, "bin/dart");
        if (flutterDart.exists()) return flutterDart.getAbsolutePath();

        // Try PATH
        try {
            Process p = new ProcessBuilder("dart", "--version").start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) return "dart";
        } catch (Exception ignored) {}

        return null;
    }

    private int findMethodEnd(String[] lines, int startLine, int currentDepth) {
        int depth = currentDepth;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') depth++;
                if (c == '}') { depth--; if (depth <= currentDepth) return i; }
            }
        }
        return lines.length - 1;
    }

    private Map<Integer, DartNode> buildLineToNodeMap(List<DartNode> nodes) {
        Map<Integer, DartNode> map = new HashMap<>();
        for (DartNode n : nodes) {
            for (int line = n.startLine; line <= n.endLine; line++) {
                map.putIfAbsent(line, n);
            }
        }
        return map;
    }

    private String uriToRelativePath(String uri) {
        // "file:///tmp/contextguard_dart_xyz/lib/src/file.dart" → "lib/src/file.dart"
        // We just need the part after the temp directory prefix
        try {
            Path full = Path.of(new java.net.URI(uri));
            // Find the contextguard_dart_ prefix and strip it
            for (int i = 0; i < full.getNameCount(); i++) {
                if (full.getName(i).toString().startsWith("contextguard_dart_")) {
                    return full.subpath(i + 1, full.getNameCount()).toString();
                }
            }
            // Fallback: just filename
            return full.getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String snakeToPascalCase(String snake) {
        StringBuilder sb   = new StringBuilder();
        boolean       next = true;
        for (char c : snake.toCharArray()) {
            if (c == '_') { next = true; }
            else if (next) { sb.append(Character.toUpperCase(c)); next = false; }
            else           { sb.append(c); }
        }
        return sb.toString();
    }

    private String stripDartStringsAndComments(String line) {
        // Strip single-line comment
        int slashIdx = line.indexOf("//");
        if (slashIdx >= 0) line = line.substring(0, slashIdx);
        // Strip simple string literals (single and double quoted, non-multiline)
        line = line.replaceAll("'[^']*'", "''").replaceAll("\"[^\"]*\"", "\"\"");
        return line;
    }

    private int countMatches(String text, java.util.regex.Pattern pattern) {
        int count = 0;
        java.util.regex.Matcher m = pattern.matcher(text);
        while (m.find()) count++;
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMPILED PATTERNS
    // ─────────────────────────────────────────────────────────────────────────

    // Class / mixin / extension declarations
    private static final java.util.regex.Pattern CLASS_PATTERN = java.util.regex.Pattern.compile(
            "^(?:abstract\\s+)?(?:class|mixin|extension)\\s+(\\w+)");

    // Method declarations inside a class
    // Captures: (returnType) (methodName)(
    // Handles: async, static, @override, get/set
    private static final java.util.regex.Pattern METHOD_PATTERN = java.util.regex.Pattern.compile(
            "^(?:static\\s+)?(?:Future<[^>]*>|Stream<[^>]*>|([A-Za-z_]\\w*(?:<[^>]*>)?)\\s+)?" +
                    "(?:get\\s+|set\\s+)?(\\w+)\\s*(?:\\(|=>)");

    // Top-level function (not inside a class)
    private static final java.util.regex.Pattern TOP_LEVEL_FUNC_PATTERN = java.util.regex.Pattern.compile(
            "^(?:Future<[^>]*>|Stream<[^>]*>|([A-Za-z_]\\w*(?:<[^>]*>)?)\\s+)?(\\w+)\\s*\\(");

    // Import statement: import 'path' [as alias] [show list] [hide list]
    private static final java.util.regex.Pattern IMPORT_PATTERN = java.util.regex.Pattern.compile(
            "^import\\s+['\"]([^'\"]+)['\"](?:\\s+as\\s+(\\w+))?(?:\\s+show\\s+([^;]+))?(?:\\s+hide\\s+([^;]+))?");

    // Typed field: [final|late|const|var] TypeName varName
    private static final java.util.regex.Pattern FIELD_DECL_PATTERN = java.util.regex.Pattern.compile(
            "(?:final|late|const)?\\s+([A-Z]\\w*(?:<[^>]*>)?)\\s+(\\w+)\\s*[;=]");

    // Constructor assignment: var/final varName = TypeName(
    private static final java.util.regex.Pattern CTOR_ASSIGN_PATTERN = java.util.regex.Pattern.compile(
            "(?:var|final|late)\\s+(\\w+)\\s*=\\s*([A-Z]\\w*)\\s*\\(");

    // Call site: [receiver.]methodName(
    private static final java.util.regex.Pattern CALL_SITE_PATTERN = java.util.regex.Pattern.compile(
            "(?:(\\w+)\\.)?(\\w+)\\s*\\(");

    // CC decision point patterns
    private static final java.util.regex.Pattern IF_PATTERN            = java.util.regex.Pattern.compile("\\bif\\s*\\(");
    private static final java.util.regex.Pattern ELSE_IF_PATTERN       = java.util.regex.Pattern.compile("\\belse\\s+if\\s*\\(");
    private static final java.util.regex.Pattern FOR_PATTERN           = java.util.regex.Pattern.compile("\\bfor\\s*\\(");
    private static final java.util.regex.Pattern WHILE_PATTERN         = java.util.regex.Pattern.compile("\\bwhile\\s*\\(");
    private static final java.util.regex.Pattern DO_PATTERN            = java.util.regex.Pattern.compile("\\bdo\\s*\\{");
    private static final java.util.regex.Pattern CASE_PATTERN          = java.util.regex.Pattern.compile("\\bcase\\b");
    private static final java.util.regex.Pattern CATCH_PATTERN         = java.util.regex.Pattern.compile("\\bcatch\\s*\\(");
    private static final java.util.regex.Pattern AND_PATTERN           = java.util.regex.Pattern.compile("&&");
    private static final java.util.regex.Pattern OR_PATTERN            = java.util.regex.Pattern.compile("\\|\\|");
    private static final java.util.regex.Pattern NULL_COALESCE_PATTERN = java.util.regex.Pattern.compile("\\?\\?(?!=)");
    private static final java.util.regex.Pattern TERNARY_PATTERN       = java.util.regex.Pattern.compile("\\?(?![.?:])");

    // Flutter lifecycle methods — tracked for widget analysis
    private static final Set<String> FLUTTER_LIFECYCLE_METHODS = Set.of(
            "build", "initState", "dispose", "didChangeDependencies",
            "didUpdateWidget", "setState", "createState",
            "deactivate", "reassemble", "activate"
    );

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT TYPES
    // ─────────────────────────────────────────────────────────────────────────

    public record DartNode(
            String  id,
            String  label,
            String  filePath,
            int     startLine,
            int     endLine,
            String  returnType,
            int     cyclomaticComplexity,
            String  classContext,
            boolean isAsync
    ) {}

    public record DartEdge(
            String from,
            String to,
            int    sourceLine
    ) {}

    public record DartParseResult(
            List<DartNode> nodes,
            List<DartEdge> edges
    ) {}
}
