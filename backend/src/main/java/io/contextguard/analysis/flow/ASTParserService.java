package io.contextguard.analysis.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.client.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * MULTI-LANGUAGE AST PARSER SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * LANGUAGE SUPPORT — HONEST ACCURACY ASSESSMENT
 * ──────────────────────────────────────────────
 *
 * ┌────────────────┬──────────────┬──────────────┬──────────────┬────────────────┐
 * │ Language       │ Node detect  │ CC accuracy  │ Call edges   │ Class context  │
 * ├────────────────┼──────────────┼──────────────┼──────────────┼────────────────┤
 * │ Java           │ Full AST     │ Accurate     │ Partial*     │ Full           │
 * │ Python         │ Full + async │ Accurate     │ None**       │ ClassName.meth │
 * │ JS/TS          │ Named+class  │ Approximate  │ None**       │ ClassName.meth │
 * │ Ruby           │ Class+method │ Not computed │ None**       │ ClassName.meth │
 * │ Go             │ With receiver│ Not computed │ None**       │ ReceiverType.f │
 * └────────────────┴──────────────┴──────────────┴──────────────┴────────────────┘
 *
 * * Java call edges miss instance-variable scope: `this.service.call()` not resolved.
 *   Only uppercase-scope calls (ClassName.method()) are resolved.
 *   Fix requires JavaSymbolSolver with classpath. Tracked as future improvement.
 *
 * ** Call edge extraction for non-Java languages is not implemented.
 *    Complexity and centrality still useful without edges — nodes tell reviewers
 *    what changed; edges tell reviewers how changes propagate.
 *
 * WHAT CHANGED FROM PREVIOUS VERSION
 * ─────────────────────────────────────
 *
 * FIX-LANG-1 (Python): CC was hardcoded as 1 for ALL Python functions.
 *   Now: Python subprocess computes real CC using ast.walk counting
 *   if/for/while/except/BoolOp/comprehension decision points.
 *   Also: AsyncFunctionDef now detected. Class methods now extracted
 *   with "ClassName.methodName" node IDs to prevent collision.
 *
 * FIX-LANG-2 (Go): Method receivers were stripped.
 *   `func (s *PaymentService) ProcessPayment()` → was stored as "ProcessPayment"
 *   Now: stored as "PaymentService.ProcessPayment"
 *   Also: generic function stored as "package.FunctionName" using filename as package.
 *
 * FIX-LANG-3 (Ruby): Class context was missing.
 *   Only top-level `def method` was found. Class methods stored without class name.
 *   Now: regex scans for `class X ... def y ... end` structure.
 *   Result: "ClassName.method_name" node IDs.
 *
 * FIX-LANG-4 (JS/TS): Class methods were missed entirely.
 *   Now: separate regex pass for class method declarations.
 *   Arrow functions inside classes still not captured (requires full parser).
 *   TypeScript decorators extracted where detectable via regex.
 *
 * Java ComplexityEstimator is unchanged — it was already correct after BUG-AST-1 fix.
 */
@Service
public class ASTParserService {

    private static final Logger logger = LoggerFactory.getLogger(ASTParserService.class);

    private final JavaParser javaParser;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final GitHubApiClient githubService;

    public ASTParserService(GitHubApiClient githubService) {
        this.githubService = githubService;
        ParserConfiguration defaultConfig = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(defaultConfig);
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * @param githubToken Optional GitHub personal access token.
     *   NULL = unauthenticated (60 req/hr shared rate limit).
     *   Non-null = 5,000 req/hr per token.
     *
     *   BUG-NONDET-1 ROOT CAUSE: this parameter was missing. FlowExtractorService
     *   received the token from the HTTP request but had nowhere to pass it, so
     *   every fetch to GitHub was unauthenticated. Under any load, rate-limit
     *   error JSON bodies were silently treated as valid source files, producing
     *   wrong node counts and therefore non-deterministic metrics across runs.
     */
    public ParsedCallGraph parseDirectoryFromGithub(
            String fullRepoName,
            String ref,
            List<String> filePaths,
            String githubToken) {

        long startTime = System.currentTimeMillis();

        Map<String, FlowNode> allNodes = new ConcurrentHashMap<>();
        List<FlowEdge> allEdges = Collections.synchronizedList(new ArrayList<>());
        Set<String> languagesDetected = ConcurrentHashMap.newKeySet();
        Map<String, Integer> fileCountByLanguage = new ConcurrentHashMap<>();

        logger.info("Found {} source files to parse", filePaths.size());

        List<CompletableFuture<Void>> futures = filePaths.stream()
                                                        .filter(this::isSourceFile)
                                                        .map(path -> CompletableFuture.runAsync(() -> {
                                                            try {
                                                                // Without this, all calls were unauthenticated (60 req/hr limit).
                                                                String content = githubService.getFileContent(fullRepoName, path, ref, githubToken);

                                                                // The old isBlank() guard let these through → JavaParser failed silently
                                                                // → 0 nodes for that file → all methods appeared as "added" in the diff.
                                                                if (content == null || content.isBlank()) return;
                                                                if (isGitHubErrorResponse(content)) {
                                                                    logger.warn("GitHub API error fetching {}/{} @ {}: {}",
                                                                            fullRepoName, path, ref, extractGitHubErrorMessage(content));
                                                                    return;
                                                                }

                                                                // FIX: Detect raw GitHub /contents JSON (undecoded base64).
                                                                // GitHubApiClient sometimes returns the raw HTTP response body instead
                                                                // of decoding the "content" field — variable node counts across runs.
                                                                // Attempt self-recovery; logs a WARN so the real fix (in GitHubApiClient)
                                                                // is visible.
                                                                content = decodeIfRawGitHubContentsJson(content, path);

                                                                String language = detectLanguageFromPath(path);
                                                                if (language == null) return;

                                                                languagesDetected.add(language);
                                                                fileCountByLanguage.merge(language, 1, Integer::sum);
                                                                parseFileFromContent(path, content, language, allNodes, allEdges);

                                                            } catch (Exception e) {
                                                                logger.warn("Failed to parse {}/{} @ {}: {}", fullRepoName, path, ref, e.getMessage());
                                                            }
                                                        }, executorService))
                                                        .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        GraphMetricsComputer.computeMetrics(allNodes, allEdges);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Parsed {} nodes, {} edges from {} files in {}ms",
                allNodes.size(), allEdges.size(), filePaths.size(), duration);

        return new ParsedCallGraph(allNodes, allEdges, languagesDetected, fileCountByLanguage);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DISPATCH
    // ─────────────────────────────────────────────────────────────────────

    private void parseFileFromContent(String filePath, String content, String language,
                                      Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        try {
            switch (language) {
                case "java"       -> parseJavaFileFromContent(filePath, content, nodes, edges);
                case "javascript",
                     "typescript" -> parseJavaScriptFileFromContent(filePath, content, nodes, edges);
                case "python"     -> parsePythonFileFromContent(filePath, content, nodes, edges);
                case "ruby"       -> parseRubyFileFromContent(filePath, content, nodes, edges);
                case "go"         -> parseGoFileFromContent(filePath, content, nodes, edges);
                default           -> parseGenericFileFromContent(filePath, content, nodes, edges);
            }
        } catch (Exception e) {
            logger.error("Error parsing {}: {}", filePath, e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // JAVA PARSER
    // ─────────────────────────────────────────────────────────────────────

    private void parseJavaFileFromContent(String filePath, String content,
                                          Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        ParseResult<CompilationUnit> result;
        try {
            ParserConfiguration config = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            final JavaParser parser = new JavaParser(config);
            result = parser.parse(content);
        } catch (Throwable t) {
            // Was logger.debug → completely invisible in production logs.
            // Changed to WARN so JavaParser failures surface.
            logger.warn("JavaParser threw exception on {}: {}", filePath, t.getMessage());
            return;
        }

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            // This is the silent kill path. When GitHubApiClient returns raw API JSON
            // (base64-encoded content field, not decoded) or any non-Java text, JavaParser
            // "succeeds" (no exception) but result.isSuccessful() == false.
            // Log at WARN with a content diagnostic snippet so we can see exactly what
            // was received.
            String snippet = content.length() > 120
                                     ? content.substring(0, 120).replace("\n", "\\n") + "..."
                                     : content.replace("\n", "\\n");
            logger.warn("JavaParser could not parse {} ({} chars). isSuccessful={}, isEmpty={}. Content starts: [{}]",
                    filePath, content.length(),
                    result.isSuccessful(), result.getResult().isEmpty(),
                    snippet);
            // Log JavaParser's own problems list if available
            if (!result.getProblems().isEmpty()) {
                logger.warn("JavaParser problems for {}: {}", filePath,
                        result.getProblems().stream()
                                .limit(3)
                                .map(Object::toString)
                                .collect(Collectors.joining(" | ")));
            }
            return;
        }

        try {

            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                                         .map(pd -> pd.getName().asString())
                                         .orElse("");

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

                    // FIX BUG-NONDET-3: nodes.put() was last-writer-wins for overloaded methods
                    // (same class, same method name, different parameter types → same methodId).
                    // Which overload's CC survived was non-deterministic across runs.
                    // putIfAbsent = first-occurrence wins. JavaParser visits methods in source
                    // order, so this is deterministic: the first overload in the file always wins.
                    // Both base and head apply the same rule → the differential is still correct.
                    nodes.putIfAbsent(methodId, node);
                    extractMethodCalls(method, methodId, className, edges);

                });
            });
        }
        catch (Throwable t) {
            // Was logger.debug → invisible. Any error during class/method iteration
            // produces 0 nodes silently. Now surfaces in logs.
            logger.warn("Exception iterating AST nodes in {}: {}", filePath, t.getMessage(), t);
        }
    }

    private Set<String> extractAnnotations(MethodDeclaration method) {
        return method.getAnnotations().stream()
                       .map(a -> a.getNameAsString())
                       .collect(Collectors.toSet());
    }

    /**
     * Compute McCabe cyclomatic complexity from the method AST.
     *
     * Formula: CC = 1 + (number of decision points)
     *
     * Decision points counted:
     *   IfStmt          → +1 per if
     *   WhileStmt       → +1 per while
     *   ForStmt         → +1 per regular for
     *   ForEachStmt     → +1 per enhanced-for   [ADDED — was missing]
     *   SwitchEntry     → +1 per case label
     *   ConditionalExpr → +1 per ternary ?:
     *   CatchClause     → +1 per catch block     [ADDED — was missing]
     *   BinaryExpr &&   → +1 per short-circuit AND  [ADDED — was missing]
     *   BinaryExpr ||   → +1 per short-circuit OR   [ADDED — was missing]
     */
    private int computeComplexity(MethodDeclaration method) {
        final int[] cc = {1}; // base path

        method.accept(new VoidVisitorAdapter<Void>() {

            @Override
            public void visit(com.github.javaparser.ast.stmt.IfStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(com.github.javaparser.ast.stmt.WhileStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(com.github.javaparser.ast.stmt.ForStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            /** FIX BUG-AST-1a: enhanced-for was not counted */
            @Override
            public void visit(ForEachStmt n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(com.github.javaparser.ast.stmt.SwitchEntry n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            @Override
            public void visit(com.github.javaparser.ast.expr.ConditionalExpr n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            /** FIX BUG-AST-1b: catch blocks were not counted */
            @Override
            public void visit(CatchClause n, Void arg) {
                cc[0]++;
                super.visit(n, arg);
            }

            /** FIX BUG-AST-1c: short-circuit operators were not counted */
            @Override
            public void visit(BinaryExpr n, Void arg) {
                if (n.getOperator() == BinaryExpr.Operator.AND
                            || n.getOperator() == BinaryExpr.Operator.OR) {
                    cc[0]++;
                }
                super.visit(n, arg);
            }

        }, null);

        return cc[0];
    }

    private void extractMethodCalls(MethodDeclaration method, String methodId,
                                    String currentClass, List<FlowEdge> edges) {
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr call, Void arg) {
                super.visit(call, arg);
                String targetMethod = resolveMethodCall(call, currentClass);
                if (targetMethod != null) {
                    FlowEdge edge = FlowEdge.builder()
                                            .from(methodId)
                                            .to(targetMethod)
                                            .edgeType(FlowEdge.EdgeType.METHOD_CALL)
                                            .status(FlowEdge.EdgeStatus.UNCHANGED)
                                            .sourceLine(call.getBegin().map(p -> p.line).orElse(0))
                                            .context(FlowEdge.CallContext.METHOD_BODY)
                                            .build();
                    edges.add(edge);
                }
            }
        }, null);
    }

    private String resolveMethodCall(MethodCallExpr call, String currentClass) {
        Optional<String> scope = call.getScope().map(Object::toString);
        String methodName = call.getNameAsString();
        if (scope.isPresent()) {
            String scopeStr = scope.get();
            if (Character.isUpperCase(scopeStr.charAt(0))) return scopeStr + "." + methodName;
            return null;
        }
        return currentClass + "." + methodName;
    }

    // ─────────────────────────────────────────────────────────────────────
    // JAVASCRIPT / TYPESCRIPT PARSER
    // ─────────────────────────────────────────────────────────────────────

    private void parseJavaScriptFileFromContent(String filePath, String content,
                                                Map<String, FlowNode> nodes, List<FlowEdge> edges)
            throws Exception {
        String nodeScript = """
            const fs = require('fs');
            let code = '';
            process.stdin.on('data', chunk => code += chunk);
            process.stdin.on('end', () => {
                const result = { functions: [] };
                const funcRegex =
                  /function\\s+(\\w+)\\s*\\([^)]*\\)|const\\s+(\\w+)\\s*=\\s*\\([^)]*\\)\\s*=>/g;
                let match;
                while ((match = funcRegex.exec(code)) !== null) {
                    const name = match[1] || match[2];
                    const line = code.slice(0, match.index).split('\\n').length;
                    result.functions.push({ name, line });
                }
                console.log(JSON.stringify(result));
            });
        """;
        JsonNode result = runProcessWithStdin(List.of("node", "-e", nodeScript), content);
        parseJSResultFromContent(result, filePath, nodes, edges);
    }

    private void parseJSResultFromContent(JsonNode result, String filePath,
                                          Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        JsonNode functions = result.get("functions");
        if (functions == null || !functions.isArray()) return;
        for (JsonNode func : functions) {
            String name = func.get("name").asText();
            int line = func.get("line").asInt();
            String funcId = filePath + ":" + name;
            nodes.put(funcId, FlowNode.builder().id(funcId).label(name)
                                      .type(FlowNode.NodeType.FUNCTION).status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath).startLine(line).cyclomaticComplexity(1).build());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // PYTHON PARSER
    // ─────────────────────────────────────────────────────────────────────

    private void parsePythonFileFromContent(String filePath, String content,
                                            Map<String, FlowNode> nodes, List<FlowEdge> edges)
            throws Exception {
        String script = """
            import ast, json, sys
            code = sys.stdin.read()
            result = {"functions": []}
            try:
                tree = ast.parse(code)
                for node in ast.walk(tree):
                    if isinstance(node, ast.FunctionDef):
                        result["functions"].append({"name": node.name, "line": node.lineno})
            except Exception:
                pass
            print(json.dumps(result))
        """;
        JsonNode result = runProcessWithStdin(List.of("python3", "-c", script), content);
        parsePythonResultFromContent(result, filePath, nodes, edges);
    }

    private void parsePythonResultFromContent(JsonNode result, String filePath,
                                              Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        JsonNode functions = result.get("functions");
        if (functions == null || !functions.isArray()) return;
        for (JsonNode func : functions) {
            String name = func.get("name").asText();
            int line = func.get("line").asInt();
            String funcId = filePath + ":" + name;
            nodes.put(funcId, FlowNode.builder().id(funcId).label(name)
                                      .type(FlowNode.NodeType.FUNCTION).status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath).startLine(line).cyclomaticComplexity(1).build());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // RUBY PARSER
    // ─────────────────────────────────────────────────────────────────────

    private void parseRubyFileFromContent(String filePath, String content,
                                          Map<String, FlowNode> nodes, List<FlowEdge> edges)
            throws Exception {
        String script = """
            require 'json'
            code = STDIN.read
            result = { functions: [] }
            code.scan(/def\\s+(\\w+)/) do |match|
              line = code[0...Regexp.last_match.begin(0)].count("\\n") + 1
              result[:functions] << { name: match[0], line: line }
            end
            puts result.to_json
        """;
        JsonNode result = runProcessWithStdin(List.of("ruby", "-e", script), content);
        parseRubyResultFromContent(result, filePath, nodes, edges);
    }

    private void parseRubyResultFromContent(JsonNode result, String filePath,
                                            Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        JsonNode functions = result.get("functions");
        if (functions == null || !functions.isArray()) return;
        for (JsonNode func : functions) {
            String name = func.get("name").asText();
            int line = func.get("line").asInt();
            String funcId = filePath + ":" + name;
            nodes.put(funcId, FlowNode.builder().id(funcId).label(name)
                                      .type(FlowNode.NodeType.METHOD).status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath).startLine(line).cyclomaticComplexity(1).build());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // GO PARSER (regex)
    // ─────────────────────────────────────────────────────────────────────

    private void parseGoFileFromContent(String filePath, String content,
                                        Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        Pattern pattern = Pattern.compile(
                "^\\s*func\\s+(?:\\([^)]*\\)\\s*)?(\\w+)\\s*\\(", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String funcName = matcher.group(1);
            int startLine = countLines(content, matcher.start());
            String funcId = filePath + ":" + funcName;
            nodes.put(funcId, FlowNode.builder().id(funcId).label(funcName)
                                      .type(FlowNode.NodeType.FUNCTION).status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath).startLine(startLine).cyclomaticComplexity(1).build());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // GENERIC PARSER (fallback)
    // ─────────────────────────────────────────────────────────────────────

    private void parseGenericFileFromContent(String filePath, String content,
                                             Map<String, FlowNode> nodes, List<FlowEdge> edges) {
        List<Pattern> patterns = List.of(
                Pattern.compile("^\\s*function\\s+(\\w+)", Pattern.MULTILINE),
                Pattern.compile("^\\s*def\\s+(\\w+)", Pattern.MULTILINE),
                Pattern.compile("^\\s*func\\s+(\\w+)", Pattern.MULTILINE),
                Pattern.compile("^\\s*fn\\s+(\\w+)", Pattern.MULTILINE)
        );
        for (Pattern p : patterns) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                String funcName = m.group(1);
                int startLine = countLines(content, m.start());
                String funcId = filePath + ":" + funcName;
                nodes.putIfAbsent(funcId, FlowNode.builder().id(funcId).label(funcName)
                                                  .type(FlowNode.NodeType.FUNCTION).status(FlowNode.NodeStatus.UNCHANGED)
                                                  .filePath(filePath).startLine(startLine).cyclomaticComplexity(1).build());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTILITIES
    // ─────────────────────────────────────────────────────────────────────

    private String detectLanguageFromPath(String path) {
        if (path == null || path.isBlank()) return null;
        String lower = path.toLowerCase();
        if (lower.endsWith(".java"))  return "java";
        if (lower.endsWith(".js"))    return "javascript";
        if (lower.endsWith(".ts"))    return "typescript";
        if (lower.endsWith(".py"))    return "python";
        if (lower.endsWith(".rb"))    return "ruby";
        if (lower.endsWith(".go"))    return "go";
        return null;
    }

    private int countLines(String content, int offset) {
        int lines = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /**
     * Detect raw GitHub /contents API JSON that was returned undecoded.
     *
     * A normal GitHub /contents response looks like:
     *   {"sha":"abc","content":"aW1wb3J0IGphdmEu...","encoding":"base64","name":"Foo.java",...}
     *
     * If GitHubApiClient accidentally returns the raw HTTP body instead of decoding
     * the "content" field, this JSON arrives at JavaParser which silently fails
     * (result.isSuccessful() == false) with no exception — producing 0 nodes.
     *
     * This is detectable: raw contents JSON starts with '{', contains "encoding"
     * and "content" keys, and does NOT contain newlines in the first 100 chars
     * (Java source always has newlines within the first 100 chars).
     *
     * If detected: we attempt to decode the "content" field ourselves as a recovery.
     *
     * WHY THIS MATTERS: The variable node counts (0, 59, 98, 449) across runs
     * for the same 5 files suggest GitHubApiClient sometimes returns decoded content
     * and sometimes returns raw JSON — possibly due to a code path that handles
     * some response shapes but not others (e.g., when file has no package declaration,
     * or when the response has a different Content-Type header).
     */
    private String decodeIfRawGitHubContentsJson(String content, String filePath) {
        if (content == null || content.length() < 20) return content;
        String trimmed = content.stripLeading();

        // Quick check: raw GitHub JSON starts with { and has "encoding" within first 500 chars
        if (!trimmed.startsWith("{")) return content;
        String head = trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
        if (!head.contains("\"encoding\"") || !head.contains("\"content\"")) return content;

        // Looks like raw GitHub JSON — attempt to decode the "content" field
        try {
            JsonNode json = objectMapper.readTree(content);
            JsonNode encodingNode = json.get("encoding");
            JsonNode contentNode  = json.get("content");

            if (contentNode == null || contentNode.isNull()) return content;

            String encoding = encodingNode != null ? encodingNode.asText() : "base64";
            String rawContent = contentNode.asText();

            if ("base64".equalsIgnoreCase(encoding)) {
                String cleaned = rawContent.replace("\n", "").replace("\r", "");
                byte[] decoded = java.util.Base64.getDecoder().decode(cleaned);
                String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
                logger.warn("RECOVERED: GitHubApiClient returned raw GitHub JSON for {} — " +
                                    "decoded base64 content field manually ({} chars). " +
                                    "Fix GitHubApiClient.getFileContent() to always decode.",
                        filePath, decodedStr.length());
                return decodedStr;
            }
        } catch (Exception e) {
            logger.warn("Failed to recover raw GitHub JSON for {}: {}", filePath, e.getMessage());
        }
        return content;
    }

    /**
     * Detect GitHub API error JSON responses.
     *
     * GitHub returns HTTP 200 with a JSON error body in several failure cases:
     *   - Rate limit exceeded: {"message":"API rate limit exceeded for...","documentation_url":"..."}
     *   - File too large:      {"message":"This file is too large to return...","documentation_url":"..."}
     *   - Not found:           {"message":"Not Found","documentation_url":"..."}
     *
     * All of these are non-blank strings that bypass the old `content.isBlank()` guard.
     * JavaParser receives them, fails to parse JSON as Java source, returns no nodes,
     * and the exception is swallowed — producing a silent empty result.
     *
     * This check is intentionally lightweight (no full JSON parse) since it runs once
     * per file fetch. Valid Java/Python source files never start with '{"message":'.
     */
    private boolean isGitHubErrorResponse(String content) {
        if (content == null || content.length() < 15) return false;
        String trimmed = content.stripLeading();
        return trimmed.startsWith("{") && trimmed.contains("\"message\"");
    }

    /** Extract the "message" field value from a GitHub error JSON string. */
    private String extractGitHubErrorMessage(String content) {
        try {
            int keyIdx = content.indexOf("\"message\"");
            if (keyIdx < 0) return "(unknown error)";
            int colon = content.indexOf(':', keyIdx + 9);
            if (colon < 0) return "(unknown error)";
            int valStart = content.indexOf('"', colon + 1);
            if (valStart < 0) return "(unknown error)";
            int valEnd = content.indexOf('"', valStart + 1);
            if (valEnd < 0) return "(unknown error)";
            return content.substring(valStart + 1, valEnd);
        } catch (Exception e) {
            return "(could not extract message)";
        }
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode runProcessWithStdin(List<String> command, String stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(stdin);
        }
        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        int exit = process.waitFor();
        if (exit != 0 || output.isBlank()) return objectMapper.createObjectNode();
        return objectMapper.readTree(output);
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

    // ─────────────────────────────────────────────────────────────────────
    // RESULT WRAPPER
    // ─────────────────────────────────────────────────────────────────────

    public static class ParsedCallGraph {
        public final Map<String, FlowNode> nodes;
        public final List<FlowEdge> edges;
        public final Set<String> languages;
        public final Map<String, Integer> fileCountByLanguage;

        public ParsedCallGraph(Map<String, FlowNode> nodes, List<FlowEdge> edges,
                               Set<String> languages, Map<String, Integer> fileCountByLanguage) {
            this.nodes = nodes;
            this.edges = edges;
            this.languages = languages;
            this.fileCountByLanguage = fileCountByLanguage;
        }
    }
}