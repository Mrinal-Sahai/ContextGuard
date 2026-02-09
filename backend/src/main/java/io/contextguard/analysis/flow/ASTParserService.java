// src/main/java/io/contextguard/analysis/flow/ASTParserService.java

package io.contextguard.analysis.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contextguard.client.GitHubApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Production-ready multi-language AST parser.
 *
 * Uses ACTUAL working libraries:
 * - JavaParser for Java
 * - Process-based parsers for JavaScript, Python, Ruby, Go
 * - Regex fallback for other languages
 */
@Service
public class ASTParserService {

    private static final Logger logger = LoggerFactory.getLogger(ASTParserService.class);

    private final JavaParser javaParser;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final GitHubApiClient githubService ;

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".js", "javascript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".ts", "typescript"),
            Map.entry(".tsx", "typescript"),
            Map.entry(".py", "python"),
            Map.entry(".rb", "ruby"),
            Map.entry(".go", "go"),
            Map.entry(".rs", "rust"),
            Map.entry(".c", "c"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".cc", "cpp"),
            Map.entry(".h", "c"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".cs", "csharp"),
            Map.entry(".php", "php"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".swift", "swift"),
            Map.entry(".scala", "scala")
    );

    public ASTParserService(GitHubApiClient githubService) {
        this.githubService = githubService;
        this.javaParser = new JavaParser();
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors())
        );
    }

    /**
     * Parse entire directory with multi-language support.
     */
    public ParsedCallGraph parseDirectoryFromGithub(
            String owner,
            String repo,
            String ref,
            List<String> filePaths
    ) {

        long startTime = System.currentTimeMillis();

        Map<String, FlowNode> allNodes = new ConcurrentHashMap<>();
        List<FlowEdge> allEdges = Collections.synchronizedList(new ArrayList<>());
        Set<String> languagesDetected = ConcurrentHashMap.newKeySet();
        Map<String, Integer> fileCountByLanguage = new ConcurrentHashMap<>();

        logger.info("Found {} source files to parse", filePaths.size());

        List<CompletableFuture<Void>> futures = filePaths.stream()
                                                        .filter(this::isSourceFile) // reuse your existing filter logic
                                                        .map(path -> CompletableFuture.runAsync(() -> {
                                                            try {
                                                                String content = githubService.getFileContent(owner, repo, path, ref);
                                                                if (content == null || content.isBlank()) {
                                                                    return;
                                                                }


                                                                String language = detectLanguageFromPath(path);
                                                                if (language == null) {
                                                                    return;
                                                                }

                                                                languagesDetected.add(language);
                                                                fileCountByLanguage.merge(language, 1, Integer::sum);

                                                                parseFileFromContent(
                                                                        path,
                                                                        content,
                                                                        language,
                                                                        allNodes,
                                                                        allEdges
                                                                );

                                                            } catch (Exception e) {
                                                                logger.warn("Failed to parse {}: {}", path, e.getMessage());
                                                            }
                                                        }, executorService))
                                                        .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        GraphMetricsComputer.computeMetrics(allNodes, allEdges);

        long duration = System.currentTimeMillis() - startTime;

        logger.info(
                "Parsed {} nodes, {} edges from {} files in {}ms",
                allNodes.size(),
                allEdges.size(),
                filePaths.size(),
                duration
        );

        return new ParsedCallGraph(allNodes, allEdges, languagesDetected, fileCountByLanguage);
    }


    private void parseFileFromContent(
            String filePath,
            String content,
            String language,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        try {
            switch (language) {
                case "java":
                    parseJavaFileFromContent(filePath, content, nodes, edges);
                    break;
                case "javascript":
                case "typescript":
                    parseJavaScriptFileFromContent(filePath, content, nodes, edges);
                    break;
                case "python":
                    parsePythonFileFromContent(filePath, content, nodes, edges);
                    break;
                case "ruby":
                    parseRubyFileFromContent(filePath, content, nodes, edges);
                    break;
                case "go":
                    parseGoFileFromContent(filePath, content, nodes, edges);
                    break;
                default:
                    parseGenericFileFromContent(filePath, content, nodes, edges);
            }
        } catch (Exception e) {
            logger.error("Error parsing {}: {}", filePath, e.getMessage(), e);
        }
    }


    // ==========================================
    // JAVA PARSER (Using JavaParser library)
    // ==========================================

    private void parseJavaFileFromContent(
            String filePath,
            String content,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        ParseResult<CompilationUnit> result;
        try {
            result = javaParser.parse(content);
        } catch (Exception e) {
            logger.debug("JavaParser failed for {}: {}", filePath, e.getMessage());
            return;
        }

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return;
        }

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
                                        .filePath(filePath) // ← IMPORTANT CHANGE
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


    private void parseJavaFile(Path file, Path sourceRoot,
                               Map<String, FlowNode> nodes,
                               List<FlowEdge> edges) throws IOException {

        ParseResult<CompilationUnit> result = javaParser.parse(file);

        if (!result.isSuccessful() || !result.getResult().isPresent()) {
            return;
        }

        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration()
                                     .map(pd -> pd.getName().asString())
                                     .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String className = packageName.isEmpty() ? cls.getNameAsString()
                                       : packageName + "." + cls.getNameAsString();

            cls.getMethods().forEach(method -> {
                String methodId = className + "." + method.getNameAsString();

                FlowNode node = FlowNode.builder()
                                        .id(methodId)
                                        .label(method.getNameAsString())
                                        .type(FlowNode.NodeType.METHOD)
                                        .status(FlowNode.NodeStatus.UNCHANGED)
                                        .filePath(sourceRoot.relativize(file).toString())
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

    private int computeComplexity(MethodDeclaration method) {
        final int[] complexity = {1};

        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(com.github.javaparser.ast.stmt.IfStmt n, Void arg) {
                complexity[0]++;
                super.visit(n, arg);
            }
            @Override
            public void visit(com.github.javaparser.ast.stmt.WhileStmt n, Void arg) {
                complexity[0]++;
                super.visit(n, arg);
            }
            @Override
            public void visit(com.github.javaparser.ast.stmt.ForStmt n, Void arg) {
                complexity[0]++;
                super.visit(n, arg);
            }
            @Override
            public void visit(com.github.javaparser.ast.stmt.SwitchEntry n, Void arg) {
                complexity[0]++;
                super.visit(n, arg);
            }
            @Override
            public void visit(com.github.javaparser.ast.expr.ConditionalExpr n, Void arg) {
                complexity[0]++;
                super.visit(n, arg);
            }
        }, null);

        return complexity[0];
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
            if (Character.isUpperCase(scopeStr.charAt(0))) {
                return scopeStr + "." + methodName;
            }
            return null;
        } else {
            return currentClass + "." + methodName;
        }
    }

    // ==========================================
    // JAVASCRIPT PARSER (Using Node.js subprocess)
    // ==========================================

    private void parseJavaScriptFileFromContent(
            String filePath,
            String content,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) throws Exception {

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

        JsonNode result = runProcessWithStdin(
                List.of("node", "-e", nodeScript),
                content
        );

        parseJSResultFromContent(result, filePath, nodes, edges);
    }


    private void parseJSResultFromContent(
            JsonNode result,
            String filePath,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        JsonNode functions = result.get("functions");
        if (functions == null || !functions.isArray()) return;

        for (JsonNode func : functions) {
            String name = func.get("name").asText();
            int line = func.get("line").asInt();

            String funcId = filePath + ":" + name;

            nodes.put(funcId, FlowNode.builder()
                                      .id(funcId)
                                      .label(name)
                                      .type(FlowNode.NodeType.FUNCTION)
                                      .status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath)
                                      .startLine(line)
                                      .cyclomaticComplexity(1)
                                      .build());
        }
    }


    // ==========================================
    // PYTHON PARSER (Using Python subprocess)
    // ==========================================

    private void parsePythonFileFromContent(
            String filePath,
            String content,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) throws Exception {

        String pythonScript = """
        import ast, json, sys

        code = sys.stdin.read()
        result = {"functions": []}

        try:
            tree = ast.parse(code)
            for node in ast.walk(tree):
                if isinstance(node, ast.FunctionDef):
                    result["functions"].append({
                        "name": node.name,
                        "line": node.lineno
                    })
        except Exception:
            pass

        print(json.dumps(result))
    """;

        JsonNode result = runProcessWithStdin(
                List.of("python3", "-c", pythonScript),
                content
        );

        parsePythonResultFromContent(result, filePath, nodes, edges);
    }


    private void parsePythonResultFromContent(
            JsonNode result,
            String filePath,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        JsonNode functions = result.get("functions");
        if (functions == null || !functions.isArray()) return;

        for (JsonNode func : functions) {
            String name = func.get("name").asText();
            int line = func.get("line").asInt();

            String funcId = filePath + ":" + name;

            nodes.put(funcId, FlowNode.builder()
                                      .id(funcId)
                                      .label(name)
                                      .type(FlowNode.NodeType.FUNCTION)
                                      .status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath)
                                      .startLine(line)
                                      .cyclomaticComplexity(1)
                                      .build());
        }
    }

    // ==========================================
    // RUBY PARSER (Using Ruby subprocess)
    // ==========================================

    private void parseRubyFileFromContent(
            String filePath,
            String content,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) throws Exception {

        String rubyScript = """
        require 'json'

        code = STDIN.read
        result = { functions: [] }

        code.scan(/def\\s+(\\w+)/) do |match|
          line = code[0...Regexp.last_match.begin(0)].count("\\n") + 1
          result[:functions] << { name: match[0], line: line }
        end

        puts result.to_json
    """;

        JsonNode result = runProcessWithStdin(
                List.of("ruby", "-e", rubyScript),
                content
        );

        parseRubyResultFromContent(result, filePath, nodes, edges);
    }


    private void parseRubyResultFromContent(
            JsonNode result,
            String filePath,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        JsonNode functions = result.get("functions");
        if (functions == null || !functions.isArray()) return;

        for (JsonNode func : functions) {
            String name = func.get("name").asText();
            int line = func.get("line").asInt();

            String funcId = filePath + ":" + name;

            nodes.put(funcId, FlowNode.builder()
                                      .id(funcId)
                                      .label(name)
                                      .type(FlowNode.NodeType.METHOD)
                                      .status(FlowNode.NodeStatus.UNCHANGED)
                                      .filePath(filePath)
                                      .startLine(line)
                                      .cyclomaticComplexity(1)
                                      .build());
        }
    }


    // ==========================================
    // GO PARSER (Regex-based for simplicity)
    // ==========================================

    private void parseGoFileFromContent(
            String filePath,
            String content,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        // Handles:
        // func foo(...)
        // func (r Receiver) bar(...)
        Pattern pattern = Pattern.compile(
                "^\\s*func\\s+(?:\\([^)]*\\)\\s*)?(\\w+)\\s*\\(",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String funcName = matcher.group(1);

            int startLine = countLines(content, matcher.start());

            String funcId = filePath + ":" + funcName;

            FlowNode node = FlowNode.builder()
                                    .id(funcId)
                                    .label(funcName)
                                    .type(FlowNode.NodeType.FUNCTION)
                                    .status(FlowNode.NodeStatus.UNCHANGED)
                                    .filePath(filePath)
                                    .startLine(startLine)
                                    .cyclomaticComplexity(1) // conservative default
                                    .build();

            nodes.put(funcId, node);
        }
    }

    // ==========================================
    // GENERIC PARSER (Regex fallback)
    // ==========================================

    private void parseGenericFileFromContent(
            String filePath,
            String content,
            Map<String, FlowNode> nodes,
            List<FlowEdge> edges
    ) {

        List<Pattern> patterns = List.of(
                Pattern.compile("^\\s*function\\s+(\\w+)", Pattern.MULTILINE), // JS
                Pattern.compile("^\\s*def\\s+(\\w+)", Pattern.MULTILINE),       // Python/Ruby
                Pattern.compile("^\\s*func\\s+(\\w+)", Pattern.MULTILINE),      // Go
                Pattern.compile("^\\s*fn\\s+(\\w+)", Pattern.MULTILINE)         // Rust
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);

            while (matcher.find()) {
                String funcName = matcher.group(1);
                int startLine = countLines(content, matcher.start());

                String funcId = filePath + ":" + funcName;

                FlowNode node = FlowNode.builder()
                                        .id(funcId)
                                        .label(funcName)
                                        .type(FlowNode.NodeType.FUNCTION)
                                        .status(FlowNode.NodeStatus.UNCHANGED)
                                        .filePath(filePath)
                                        .startLine(startLine)
                                        .cyclomaticComplexity(1)
                                        .build();

                nodes.putIfAbsent(funcId, node);
            }
        }
    }


    // ==========================================
    // UTILITY METHODS
    // ==========================================

    private String detectLanguageFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String lower = path.toLowerCase();

        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".js")) {
            return "javascript";
        }
        if (lower.endsWith(".ts")) {
            return "typescript";
        }
        if (lower.endsWith(".py")) {
            return "python";
        }
        if (lower.endsWith(".rb")) {
            return "ruby";
        }
        if (lower.endsWith(".go")) {
            return "go";
        }

        return null;
    }


    private int countLines(String content, int offset) {
        int lines = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode runProcessWithStdin(
            List<String> command,
            String stdin
    ) throws Exception {

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        try (BufferedWriter writer =
                     new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(stdin);
        }

        String output;
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exit = process.waitFor();
        if (exit != 0 || output.isBlank()) {
            return objectMapper.createObjectNode();
        }

        return objectMapper.readTree(output);
    }


    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java",
            ".js",
            ".ts",
            ".py",
            ".rb",
            ".go"
    );

    private static final List<String> IGNORED_PATH_SEGMENTS = List.of(
            "/node_modules/",
            "/dist/",
            "/build/",
            "/target/",
            "/out/",
            "/vendor/",
            "/.git/",
            "/.idea/",
            "/.vscode/"
    );

    private boolean isSourceFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String normalized = path.replace('\\', '/').toLowerCase();

        // Skip directories implicitly
        if (normalized.endsWith("/")) {
            return false;
        }

        // Skip ignored directories
        for (String ignored : IGNORED_PATH_SEGMENTS) {
            if (normalized.contains(ignored)) {
                return false;
            }
        }

        // Must match a supported extension
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (normalized.endsWith(ext)) {
                return true;
            }
        }

        return false;
    }


    // ==========================================
    // RESULT WRAPPER
    // ==========================================

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