package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.contextguard.client.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * MULTI-LANGUAGE AST PARSER SERVICE — TIER 2 (Full Type Resolution)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * LANGUAGE SUPPORT — ACCURACY AFTER TIER 2
 * ──────────────────────────────────────────
 *
 * ┌────────────────┬──────────────────┬──────────────────┬────────────────────┬──────────────┐
 * │ Language       │ Node detection   │ CC accuracy      │ Call edges         │ Overall est. │
 * ├────────────────┼──────────────────┼──────────────────┼────────────────────┼──────────────┤
 * │ Java           │ Full AST         │ McCabe accurate  │ ~75% (SymbolSolver)│ ~9/10        │
 * │ TypeScript     │ Full tsc AST     │ eslint-compat    │ ~85% (tsc API)     │ ~9/10        │
 * │ Python         │ Full ast + async │ radon-compat     │ ~65% (pyright)     │ ~8/10        │
 * │ Go             │ go/types full    │ gocyclo-compat   │ ~80% (go/types)    │ ~8.5/10      │
 * │ Ruby           │ Tree-sitter      │ rubocop-compat   │ ~40% (heuristic)   │ ~6.5/10      │
 * └────────────────┴──────────────────┴──────────────────┴────────────────────┴──────────────┘
 *
 * ARCHITECTURE — TWO-PASS PARSING
 * ─────────────────────────────────
 *
 * PASS 1 — GLOBAL SYMBOL INDEX CONSTRUCTION
 *   All files fetched concurrently from GitHub.
 *   For each language with a tool bridge (TypeScript, Python, Go):
 *     → entire batch sent to the bridge's "index" request
 *     → bridge returns class/method/import/field data
 *     → CrossFileSymbolIndex populated with cross-file type information
 *   For Java:
 *     → JavaSymbolSolverService.extractClassRegistrations() called per file
 *     → field types and imports extracted from each CompilationUnit
 *
 * PASS 2 — FULL PARSE WITH SYMBOL RESOLUTION
 *   For TypeScript, Python, Go:
 *     → individual "parse" requests sent to the tool bridge
 *     → bridge returns nodes + edges with type-resolved call targets
 *     → edges resolved against CrossFileSymbolIndex where bridge couldn't resolve
 *   For Java:
 *     → re-parsed with JavaSymbolSolver-aware JavaParser
 *     → JavaSymbolSolverService.extractResolvedMethodCalls() resolves edges
 *   For Ruby:
 *     → Tree-sitter bridge (no Tier 2 tool available)
 *     → edges resolved via CrossFileSymbolIndex heuristics
 *
 * FALLBACK CHAIN
 * ───────────────
 *   Tier 2 tool bridge → CrossFileSymbolIndex resolution → Tree-sitter basic parse
 *
 * Each layer degrades gracefully. Java parsing is never affected by non-Java failures.
 */
@Service
public class ASTParserService {

    private static final Logger logger = LoggerFactory.getLogger(ASTParserService.class);

    private final GitHubApiClient          githubService;
    private final TreeSitterBridgeService  treeSitterBridge;
    private final LanguageToolBridgeService langToolBridge;
    private final ObjectMapper             objectMapper;
    private final ExecutorService          executorService;

    public ASTParserService(GitHubApiClient githubService,
                            TreeSitterBridgeService treeSitterBridge,
                            LanguageToolBridgeService langToolBridge) {
        this.githubService    = githubService;
        this.treeSitterBridge = treeSitterBridge;
        this.langToolBridge   = langToolBridge;
        this.objectMapper     = new ObjectMapper();
        this.executorService  = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public ParsedCallGraph parseDirectoryFromGithub(
            String fullRepoName, String ref,
            List<String> filePaths, String githubToken) {

        long startTime = System.currentTimeMillis();

        // ── Fetch all file contents concurrently ──────────────────────────────
        Map<String, String> fileContents        = new ConcurrentHashMap<>();
        Set<String>         languagesDetected   = ConcurrentHashMap.newKeySet();
        Map<String, Integer> fileCountByLanguage = new ConcurrentHashMap<>();

        logger.info("Fetching {} source files", filePaths.size());

        List<CompletableFuture<Void>> fetchFutures = filePaths.stream()
                                                             .filter(this::isSourceFile)
                                                             .map(path -> CompletableFuture.runAsync(() -> {
                                                                 try {
                                                                     String content = githubService.getFileContent(fullRepoName, path, ref, githubToken);
                                                                     if (content == null || content.isBlank()) return;
                                                                     if (isGitHubErrorResponse(content)) {
                                                                         logger.warn("GitHub API error fetching {}: {}", path, extractGitHubErrorMessage(content));
                                                                         return;
                                                                     }
                                                                     content = decodeIfRawGitHubContentsJson(content, path);
                                                                     String language = detectLanguageFromPath(path);
                                                                     if (language == null) return;
                                                                     fileContents.put(path, content);
                                                                     languagesDetected.add(language);
                                                                     fileCountByLanguage.merge(language, 1, Integer::sum);
                                                                 } catch (Exception e) {
                                                                     logger.warn("Failed to fetch {}: {}", path, e.getMessage());
                                                                 }
                                                             }, executorService))
                                                             .collect(Collectors.toList());

        CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0])).join();
        logger.info("Fetched {} files in {}ms", fileContents.size(),
                System.currentTimeMillis() - startTime);

        // ── PASS 1: Build the global symbol index ─────────────────────────────
        CrossFileSymbolIndex symbolIndex = buildSymbolIndex(fileContents);

        // ── PASS 2: Full parse with symbol resolution ─────────────────────────
        Map<String, FlowNode> allNodes = new ConcurrentHashMap<>();
        List<FlowEdge>        allEdges = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> parseFutures = fileContents.entrySet().stream()
                                                             .map(entry -> CompletableFuture.runAsync(() -> {
                                                                 String filePath = entry.getKey();
                                                                 String content  = entry.getValue();
                                                                 String language = detectLanguageFromPath(filePath);
                                                                 if (language == null) return;
                                                                 // Build a per-thread parser — JavaParser is NOT thread-safe;
                                                                 // sharing one instance across concurrent workers corrupts
                                                                 // internal parser state (jj_scanpos, token buffers).
                                                                 JavaParser solverJavaParser = JavaSymbolSolverService.buildSolverAwareParser(symbolIndex);
                                                                 try {
                                                                     parseFilePass2(filePath, content, language,
                                                                            solverJavaParser, symbolIndex, allNodes, allEdges);
                                                                 } catch (Exception e) {
                                                                     logger.warn("Pass 2 parse failed for {}: {}", filePath, e.getMessage());
                                                                 }
                                                             }, executorService))
                                                             .collect(Collectors.toList());

        CompletableFuture.allOf(parseFutures.toArray(new CompletableFuture[0])).join();

        GraphMetricsComputer.computeMetrics(allNodes, allEdges);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Parsed {} nodes, {} edges from {} files in {}ms",
                allNodes.size(), allEdges.size(), fileContents.size(), duration);
        symbolIndex.logStats();

        return new ParsedCallGraph(allNodes, allEdges, languagesDetected, fileCountByLanguage);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASS 1 — BUILD GLOBAL SYMBOL INDEX
    // ─────────────────────────────────────────────────────────────────────────

    private CrossFileSymbolIndex buildSymbolIndex(Map<String, String> fileContents) {
        long t0 = System.currentTimeMillis();
        CrossFileSymbolIndex symbolIndex = new CrossFileSymbolIndex();

        // ── Group files by language ───────────────────────────────────────────
        Map<String, Map<String, String>> byLanguage = new HashMap<>();
        for (Map.Entry<String, String> e : fileContents.entrySet()) {
            String lang = detectLanguageFromPath(e.getKey());
            if (lang != null) {
                byLanguage.computeIfAbsent(lang, k -> new LinkedHashMap<>())
                        .put(e.getKey(), e.getValue());
            }
        }

        // ── TypeScript: send entire batch to tsc bridge ───────────────────────
        Map<String, String> tsFiles = byLanguage.getOrDefault("typescript", Map.of());
        if (!tsFiles.isEmpty()) {
            boolean handled = langToolBridge.indexBatch("typescript", tsFiles, symbolIndex);
            if (!handled) {
                // Fallback: build index from Tree-sitter parse results
                indexViaBridgeFallback(tsFiles, "typescript", symbolIndex);
            }
        }

        // ── Python: send entire batch to pyright bridge ───────────────────────
        Map<String, String> pyFiles = byLanguage.getOrDefault("python", Map.of());
        if (!pyFiles.isEmpty()) {
            boolean handled = langToolBridge.indexBatch("python", pyFiles, symbolIndex);
            if (!handled) {
                indexViaBridgeFallback(pyFiles, "python", symbolIndex);
            }
        }

        // ── Go: send entire batch to go/types bridge ──────────────────────────
        Map<String, String> goFiles = byLanguage.getOrDefault("go", Map.of());
        if (!goFiles.isEmpty()) {
            boolean handled = langToolBridge.indexBatch("go", goFiles, symbolIndex);
            if (!handled) {
                indexViaBridgeFallback(goFiles, "go", symbolIndex);
            }
        }

        // ── JavaScript: Tree-sitter only (no Tier 2 tool) ────────────────────
        byLanguage.getOrDefault("javascript", Map.of())
                .forEach((fp, content) -> indexViaBridgeSingle(fp, content, "javascript", symbolIndex));

        // ── Ruby: Tree-sitter only ────────────────────────────────────────────
        byLanguage.getOrDefault("ruby", Map.of())
                .forEach((fp, content) -> indexViaBridgeSingle(fp, content, "ruby", symbolIndex));

        // ── Java: JavaParser + JavaSymbolSolverService ────────────────────────
        // Parse each file with plain JavaParser (no solver yet — solver needs the index)
        JavaParser plainParser = new JavaParser();
        List<CompletableFuture<Void>> javaFutures = byLanguage
                                                            .getOrDefault("java", Map.of()).entrySet().stream()
                                                            .map(e -> CompletableFuture.runAsync(() ->
                                                                                                         indexJavaFile(e.getKey(), e.getValue(), plainParser, symbolIndex),
                                                                    executorService))
                                                            .collect(Collectors.toList());
        CompletableFuture.allOf(javaFutures.toArray(new CompletableFuture[0])).join();

        logger.info("Pass 1 complete in {}ms", System.currentTimeMillis() - t0);
        return symbolIndex;
    }

    private void indexJavaFile(String filePath, String content,
                               JavaParser parser, CrossFileSymbolIndex symbolIndex) {
        try {
            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()) return;
            CompilationUnit cu = result.getResult().get();
            JavaSymbolSolverService.extractClassRegistrations(cu, filePath, symbolIndex);
            JavaSymbolSolverService.extractFieldTypes(cu, filePath, symbolIndex);
            JavaSymbolSolverService.extractImports(cu, filePath, symbolIndex);
        } catch (Exception e) {
            logger.debug("Pass 1 Java indexing failed for {}: {}", filePath, e.getMessage());
        }
    }

    /** Use Tree-sitter parse result to populate the symbol index (fallback for Pass 1) */
    private void indexViaBridgeFallback(Map<String, String> files, String language,
                                        CrossFileSymbolIndex symbolIndex) {
        files.forEach((fp, content) -> indexViaBridgeSingle(fp, content, language, symbolIndex));
    }

    private void indexViaBridgeSingle(String filePath, String content, String language,
                                      CrossFileSymbolIndex symbolIndex) {
        if (!treeSitterBridge.isAvailable()) return;
        try {
            TreeSitterBridgeService.TreeSitterResult result =
                    treeSitterBridge.parse(language, filePath, content);
            for (TreeSitterBridgeService.TreeSitterResult.ParsedNode n : result.nodes) {
                if (n.classContext != null) {
                    symbolIndex.registerClass(n.classContext, n.classContext, filePath, language);
                }
                symbolIndex.registerMethod(n.label, n.id, n.classContext, filePath, language);
            }
        } catch (Exception e) {
            logger.debug("Pass 1 Tree-sitter index failed for {}: {}", filePath, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PASS 2 — FULL PARSE WITH TYPE RESOLUTION
    // ─────────────────────────────────────────────────────────────────────────

    private void parseFilePass2(String filePath, String content, String language,
                                JavaParser solverParser, CrossFileSymbolIndex symbolIndex,
                                Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        switch (language) {
            case "java"       -> parseJavaPass2(filePath, content, solverParser, symbolIndex, nodes, edges);
            case "typescript" -> parseWithToolBridge(filePath, content, "typescript", symbolIndex, nodes, edges);
            case "python"     -> parseWithToolBridge(filePath, content, "python", symbolIndex, nodes, edges);
            case "go"         -> parseWithToolBridge(filePath, content, "go", symbolIndex, nodes, edges);
            case "javascript",
                 "ruby"       -> parseViaTreeSitter(filePath, content, language, symbolIndex, nodes, edges);
            default           -> parseGenericFile(filePath, content, nodes);
        }
    }

    // ── Java: JavaSymbolSolver-enhanced parsing ───────────────────────────────

    private void parseJavaPass2(String filePath, String content, JavaParser parser,
                                CrossFileSymbolIndex symbolIndex,
                                Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(content);
        } catch (Throwable t) {
            logger.warn("JavaParser exception on {}: {}", filePath, t.getMessage());
            return;
        }

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            logJavaParseFailure(filePath, content, result);
            return;
        }

        try {
            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                                         .map(pd -> pd.getName().asString()).orElse("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                String className = packageName.isEmpty()
                                           ? cls.getNameAsString()
                                           : packageName + "." + cls.getNameAsString();

                cls.getMethods().forEach(method -> {
                    String methodId = className + "." + method.getNameAsString();

                    FlowNode node = FlowNode.builder()
                                            .id(methodId)
                                            .label(method.getNameAsString())
                                            .type(FlowNode.NodeType.METHOD)
                                            .status(FlowNode.NodeStatus.UNCHANGED)
                                            .filePath(filePath)
                                            .startLine(method.getBegin().map(p -> p.line).orElse(0))
                                            .endLine(method.getEnd().map(p -> p.line).orElse(0))
                                            .returnType(method.getType().asString())
                                            .annotations(extractAnnotations(method))
                                            .cyclomaticComplexity(computeComplexity(method))
                                            .build();

                    nodes.putIfAbsent(methodId, node);

                    // Pass 2: use symbol-solver-aware edge extraction
                    JavaSymbolSolverService.extractResolvedMethodCalls(
                            method, methodId, className, filePath, symbolIndex, edges);
                });
            });
        } catch (Throwable t) {
            logger.warn("Exception iterating Java AST nodes in {}: {}", filePath, t.getMessage(), t);
        }
    }

    // ── TypeScript / Python / Go: tool bridge parse ───────────────────────────

    private void parseWithToolBridge(String filePath, String content, String language,
                                     CrossFileSymbolIndex symbolIndex,
                                     Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        // Attempt Tier 2 bridge first
        if (langToolBridge.isAvailable(language)) {
            LanguageToolBridgeService.ParseResult result =
                    langToolBridge.parseFile(language, filePath, content);
            if (result != null) {
                mapBridgeResultToGraph(result.nodes, result.edges, filePath, symbolIndex, nodes, edges);
                return;
            }
        }
        // Fallback: Tree-sitter with symbol index edge resolution
        logger.debug("Falling back to Tree-sitter for {} ({})", filePath, language);
        parseViaTreeSitter(filePath, content, language, symbolIndex, nodes, edges);
    }

    // ── Tree-sitter with post-hoc edge resolution ─────────────────────────────

    private void parseViaTreeSitter(String filePath, String content, String language,
                                    CrossFileSymbolIndex symbolIndex,
                                    Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        if (!treeSitterBridge.isAvailable()) {
            logger.warn("Tree-sitter bridge unavailable — skipping {} ({})", filePath, language);
            return;
        }
        try {
            TreeSitterBridgeService.TreeSitterResult result =
                    treeSitterBridge.parse(language, filePath, content);
            mapBridgeResultToGraph(result.nodes, result.edges, filePath, symbolIndex, nodes, edges);
        } catch (Exception e) {
            logger.warn("Tree-sitter parse error for {} ({}): {}", filePath, language, e.getMessage());
        }
    }
    /**
     * Map a bridge result (from either Tier 2 tools or Tree-sitter) into the
     * FlowNode/FlowEdge domain model.
     *
     * Each raw edge's "to" target is re-resolved against the CrossFileSymbolIndex.
     * If the symbol index can find a better target (cross-file, type-resolved),
     * the edge is updated. Otherwise the bridge's original target is kept.
     */
    private void mapBridgeResultToGraph(
            List<TreeSitterBridgeService.TreeSitterResult.ParsedNode> parsedNodes,
            List<TreeSitterBridgeService.TreeSitterResult.ParsedEdge> parsedEdges,
            String filePath,
            CrossFileSymbolIndex symbolIndex,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges) {

        // Map nodes
        for (TreeSitterBridgeService.TreeSitterResult.ParsedNode pn : parsedNodes) {
            FlowNode node = FlowNode.builder()
                                    .id(pn.id)
                                    .label(pn.label)
                                    .type(pn.classContext != null ? FlowNode.NodeType.METHOD : FlowNode.NodeType.FUNCTION)
                                    .status(FlowNode.NodeStatus.UNCHANGED)
                                    .filePath(pn.filePath)
                                    .startLine(pn.startLine)
                                    .endLine(pn.endLine)
                                    .returnType(pn.returnType)
                                    .annotations(new HashSet<>(pn.decorators))
                                    .cyclomaticComplexity(pn.cyclomaticComplexity)
                                    .build();
            nodes.putIfAbsent(pn.id, node);
        }

        // Map edges — with cross-file symbol resolution
        for (TreeSitterBridgeService.TreeSitterResult.ParsedEdge pe : parsedEdges) {
            String resolvedTo = pe.to;

            // Attempt to improve the edge target via the symbol index
            // Parse the raw "to" to extract receiver and method name
            String[] parts    = parseRawEdgeTarget(pe.to);
            String receiver   = parts[0];  // null for bare calls
            String methodName = parts[1];

            // Find the caller's class context
            String callerClass = extractCallerClass(pe.from, filePath);

            String betterTarget = symbolIndex.resolve(filePath, callerClass, receiver, methodName);
            if (betterTarget != null) {
                resolvedTo = betterTarget;
            }

            edges.add(FlowEdge.builder()
                              .from(pe.from)
                              .to(resolvedTo)
                              .edgeType(FlowEdge.EdgeType.METHOD_CALL)
                              .status(FlowEdge.EdgeStatus.UNCHANGED)
                              .sourceLine(pe.sourceLine)
                              .context(FlowEdge.CallContext.METHOD_BODY)
                              .build());
        }
    }

    /**
     * Parse a raw edge target like "ClassName.methodName" or "filePath:ClassName.methodName"
     * into [receiver, methodName].
     */
    private String[] parseRawEdgeTarget(String rawTo) {
        if (rawTo == null) return new String[]{null, ""};

        // Strip filePath prefix if present: "src/foo.py:ClassName.method" → "ClassName.method"
        String withoutPath = rawTo.contains(":") ? rawTo.substring(rawTo.lastIndexOf(':') + 1) : rawTo;

        int dotIdx = withoutPath.lastIndexOf('.');
        if (dotIdx > 0) {
            return new String[]{
                    withoutPath.substring(0, dotIdx),   // receiver / class name
                    withoutPath.substring(dotIdx + 1)   // method name
            };
        }
        return new String[]{null, withoutPath};
    }

    /**
     * Extract the class context from a node ID.
     * "src/foo.py:ClassName.methodName" → "ClassName"
     * "com.example.Service.doWork"      → "com.example.Service"
     */
    private String extractCallerClass(String nodeId, String filePath) {
        if (nodeId == null) return null;
        String withoutPath = nodeId.contains(":") ? nodeId.substring(nodeId.lastIndexOf(':') + 1) : nodeId;
        int dotIdx = withoutPath.lastIndexOf('.');
        if (dotIdx > 0) return withoutPath.substring(0, dotIdx);
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JAVA CC + ANNOTATION EXTRACTION  (unchanged)
    // ─────────────────────────────────────────────────────────────────────────

    private Set<String> extractAnnotations(MethodDeclaration method) {
        return method.getAnnotations().stream()
                       .map(a -> a.getNameAsString())
                       .collect(Collectors.toSet());
    }

    /**
     * McCabe CC: 1 + if + while + for + foreach + switch case + ternary + catch + && + ||
     */
    private int computeComplexity(MethodDeclaration method) {
        final int[] cc = {1};
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override public void visit(com.github.javaparser.ast.stmt.IfStmt n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(com.github.javaparser.ast.stmt.WhileStmt n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(com.github.javaparser.ast.stmt.ForStmt n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(ForEachStmt n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(com.github.javaparser.ast.stmt.SwitchEntry n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(com.github.javaparser.ast.expr.ConditionalExpr n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(CatchClause n, Void a)
            { cc[0]++; super.visit(n, a); }
            @Override public void visit(BinaryExpr n, Void a) {
                if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) cc[0]++;
                super.visit(n, a);
            }
        }, null);
        return cc[0];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERIC PARSER (fallback for unsupported extensions)
    // ─────────────────────────────────────────────────────────────────────────

    private void parseGenericFile(String filePath, String content, Map<String, FlowNode> nodes) {
        List<java.util.regex.Pattern> patterns = List.of(
                java.util.regex.Pattern.compile("^\\s*function\\s+(\\w+)", java.util.regex.Pattern.MULTILINE),
                java.util.regex.Pattern.compile("^\\s*def\\s+(\\w+)",      java.util.regex.Pattern.MULTILINE),
                java.util.regex.Pattern.compile("^\\s*func\\s+(\\w+)",     java.util.regex.Pattern.MULTILINE),
                java.util.regex.Pattern.compile("^\\s*fn\\s+(\\w+)",       java.util.regex.Pattern.MULTILINE)
        );
        for (java.util.regex.Pattern p : patterns) {
            java.util.regex.Matcher m = p.matcher(content);
            while (m.find()) {
                String funcName  = m.group(1);
                int    startLine = countLines(content, m.start());
                String funcId    = filePath + ":" + funcName;
                nodes.putIfAbsent(funcId, FlowNode.builder()
                                                  .id(funcId).label(funcName)
                                                  .type(FlowNode.NodeType.FUNCTION).status(FlowNode.NodeStatus.UNCHANGED)
                                                  .filePath(filePath).startLine(startLine).cyclomaticComplexity(1).build());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────────

    private void logJavaParseFailure(String filePath, String content,
                                     ParseResult<CompilationUnit> result) {
        String snippet = content.length() > 120
                                 ? content.substring(0, 120).replace("\n", "\\n") + "..."
                                 : content.replace("\n", "\\n");
        logger.warn("[java-parser] SKIP {} ({} chars) — {} problems: {}",
                filePath, content.length(),
                result.getProblems().size(),
                result.getProblems().stream().limit(2)
                        .map(com.github.javaparser.Problem::getMessage)
                        .collect(Collectors.joining("; ")));
    }

    private String detectLanguageFromPath(String path) {
        if (path == null || path.isBlank()) return null;
        String lower = path.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".ts"))   return "typescript";
        if (lower.endsWith(".js"))   return "javascript";
        if (lower.endsWith(".py"))   return "python";
        if (lower.endsWith(".go"))   return "go";
        if (lower.endsWith(".rb"))   return "ruby";
        return null;
    }

    private int countLines(String content, int offset) {
        int lines = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private String decodeIfRawGitHubContentsJson(String content, String filePath) {
        if (content == null || content.length() < 20) return content;
        String trimmed = content.stripLeading();
        if (!trimmed.startsWith("{")) return content;
        String head = trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
        if (!head.contains("\"encoding\"") || !head.contains("\"content\"")) return content;
        try {
            JsonNode json        = objectMapper.readTree(content);
            JsonNode encodingNode = json.get("encoding");
            JsonNode contentNode  = json.get("content");
            if (contentNode == null || contentNode.isNull()) return content;
            String encoding   = encodingNode != null ? encodingNode.asText() : "base64";
            String rawContent = contentNode.asText();
            if ("base64".equalsIgnoreCase(encoding)) {
                byte[] decoded    = java.util.Base64.getDecoder().decode(
                        rawContent.replace("\n", "").replace("\r", ""));
                String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                logger.warn("RECOVERED raw GitHub JSON for {} — decoded {} chars.", filePath, decodedStr.length());
                return decodedStr;
            }
        } catch (Exception e) {
            logger.warn("Failed to recover raw GitHub JSON for {}: {}", filePath, e.getMessage());
        }
        return content;
    }

    private boolean isGitHubErrorResponse(String content) {
        if (content == null || content.length() < 15) return false;
        String trimmed = content.stripLeading();
        return trimmed.startsWith("{") && trimmed.contains("\"message\"");
    }

    private String extractGitHubErrorMessage(String content) {
        try {
            int keyIdx   = content.indexOf("\"message\"");
            if (keyIdx < 0) return "(unknown error)";
            int colon    = content.indexOf(':', keyIdx + 9);
            if (colon < 0)  return "(unknown error)";
            int valStart = content.indexOf('"', colon + 1);
            if (valStart < 0) return "(unknown error)";
            int valEnd   = content.indexOf('"', valStart + 1);
            if (valEnd < 0)   return "(unknown error)";
            return content.substring(valStart + 1, valEnd);
        } catch (Exception e) {
            return "(unknown error)";
        }
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS))
                executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".js", ".ts", ".py", ".rb", ".go");
    private static final List<String> IGNORED_PATH_SEGMENTS = List.of(
            "/node_modules/", "/dist/", "/build/", "/target/",
            "/out/", "/vendor/", "/.git/", "/.idea/", "/.vscode/");

    private boolean isSourceFile(String path) {
        if (path == null || path.isBlank() || path.endsWith("/")) return false;
        String normalized = path.replace('\\', '/').toLowerCase();
        for (String ignored : IGNORED_PATH_SEGMENTS) {
            if (normalized.contains(ignored)) return false;
        }
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (normalized.endsWith(ext)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT WRAPPER
    // ─────────────────────────────────────────────────────────────────────────

    public static class ParsedCallGraph {
        public final Map<String, FlowNode>  nodes;
        public final List<FlowEdge>         edges;
        public final Set<String>            languages;
        public final Map<String, Integer>   fileCountByLanguage;

        public ParsedCallGraph(Map<String, FlowNode> nodes, List<FlowEdge> edges,
                               Set<String> languages, Map<String, Integer> fileCountByLanguage) {
            this.nodes               = nodes;
            this.edges               = edges;
            this.languages           = languages;
            this.fileCountByLanguage = fileCountByLanguage;
        }
    }
}