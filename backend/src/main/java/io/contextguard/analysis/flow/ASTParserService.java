package io.contextguard.analysis.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
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
 * Production-ready multi-language AST parser.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * BUGS FIXED
 * ─────────────────────────────────────────────────────────────────────────
 *
 * BUG-AST-1 (UNDERCOUNTING): computeComplexity() missed 3 branch types
 *   Before: visitor handled IfStmt, WhileStmt, ForStmt, SwitchEntry, ConditionalExpr.
 *   Missing: ForEachStmt (enhanced-for), CatchClause, BinaryExpr &&/||.
 *   After:  All 8 visitor methods now present, matching ComplexityEstimator's
 *           pattern set. The two now agree on what counts as a decision point.
 *
 *   Impact of old bug:
 *     "for (File f : files) { if (f.exists() && f.canRead()) }" → scored 2.
 *     Correct score: 4 (forEach + if + && + implicit else-of-catch).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * Everything else (parseDirectoryFromGithub, language parsers, utilities)
 * is unchanged from the previous version.
 * ─────────────────────────────────────────────────────────────────────────
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
        this.javaParser = new JavaParser();
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API — unchanged from previous version
    // ─────────────────────────────────────────────────────────────────────

    public ParsedCallGraph parseDirectoryFromGithub(
            String fullRepoName,
            String ref,
            List<String> filePaths) {

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
                                                                String content = githubService.getFileContent(fullRepoName, path, ref);
                                                                if (content == null || content.isBlank()) return;

                                                                String language = detectLanguageFromPath(path);
                                                                if (language == null) return;

                                                                languagesDetected.add(language);
                                                                fileCountByLanguage.merge(language, 1, Integer::sum);

                                                                parseFileFromContent(path, content, language, allNodes, allEdges);

                                                            } catch (Exception e) {
                                                                logger.warn("Failed to parse {}: {}", path, e.getMessage());
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
            result = javaParser.parse(content);
        } catch (Exception e) {
            logger.debug("JavaParser failed for {}: {}", filePath, e.getMessage());
            return;
        }

        if (!result.isSuccessful() || result.getResult().isEmpty()) return;

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

                nodes.put(methodId, node);
                extractMethodCalls(method, methodId, className, edges);
            });
        });
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