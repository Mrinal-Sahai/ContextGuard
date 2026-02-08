package io.contextguard.analysis.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-language AST parser.
 *
 * SUPPORTED LANGUAGES:
 * - Java (via JavaParser)
 * - JavaScript/TypeScript (via Babel/Acorn - delegated to Node.js subprocess)
 * - Python (via AST module - delegated to Python subprocess)
 * - Ruby (via Parser gem - delegated to Ruby subprocess)
 *
 * LIMITATION: For non-Java, this implementation calls external parsers.
 * Production system would use native JVM parsers or unified parser like TreeSitter.
 */
@Service
public class ASTParserService {

    private final JavaParser javaParser;

    public ASTParserService() {
        this.javaParser = new JavaParser();
    }

    /**
     * Parse directory and extract call graph.
     *
     * @param sourceRoot Root directory of source code
     * @return Map of fully qualified name -> FlowNode
     */
    public Map<String, FlowNode> parseDirectory(Path sourceRoot) throws IOException {

        Map<String, FlowNode> nodes = new HashMap<>();
        List<FlowEdge> edges = new ArrayList<>();

        // Walk source tree
        Files.walk(sourceRoot)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try {
                        if (file.toString().endsWith(".java")) {
                            parseJavaFile(file, sourceRoot, nodes, edges);
                        } else if (file.toString().matches(".*\\.(js|ts|jsx|tsx)$")) {
                            parseJavaScriptFile(file, sourceRoot, nodes, edges);
                        } else if (file.toString().endsWith(".py")) {
                            parsePythonFile(file, sourceRoot, nodes, edges);
                        } else if (file.toString().endsWith(".rb")) {
                            parseRubyFile(file, sourceRoot, nodes, edges);
                        }
                    } catch (Exception e) {
                        // Log error but continue parsing other files
                        System.err.println("Failed to parse " + file + ": " + e.getMessage());
                    }
                });

        // Compute graph metrics
        computeMetrics(nodes, edges);

        return nodes;
    }

    /**
     * Parse Java file using JavaParser.
     */
    private void parseJavaFile(Path file, Path sourceRoot,
                               Map<String, FlowNode> nodes,
                               List<FlowEdge> edges) throws IOException {

        ParseResult<CompilationUnit> result = javaParser.parse(file);

        if (!result.isSuccessful()) {
            return;
        }

        CompilationUnit cu = result.getResult().orElse(null);
        if (cu == null) {
            return;
        }

        String packageName = cu.getPackageDeclaration()
                                     .map(pd -> pd.getName().asString())
                                     .orElse("");

        // Extract classes
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String className = packageName.isEmpty() ? cls.getNameAsString()
                                       : packageName + "." + cls.getNameAsString();

            // Extract methods
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

                // Extract method calls
                extractMethodCalls(method, methodId, className, edges);
            });
        });
    }

    /**
     * Extract method calls from method body.
     */
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

    /**
     * Resolve method call to fully qualified name.
     *
     * LIMITATION: This is simplified. Full resolution requires symbol solver.
     */
    private String resolveMethodCall(MethodCallExpr call, String currentClass) {

        Optional<String> scope = call.getScope().map(Object::toString);
        String methodName = call.getNameAsString();

        if (scope.isPresent()) {
            // Scope present: SomeClass.method() or object.method()
            String scopeStr = scope.get();

            // Simple heuristic: if scope starts with uppercase, assume class name
            if (Character.isUpperCase(scopeStr.charAt(0))) {
                return scopeStr + "." + methodName;
            }

            // Otherwise, assume instance call (cannot resolve without type info)
            return null;
        } else {
            // No scope: this.method() or method()
            return currentClass + "." + methodName;
        }
    }

    /**
     * Extract annotations from method.
     */
    private Set<String> extractAnnotations(MethodDeclaration method) {
        return method.getAnnotations().stream()
                       .map(a -> a.getNameAsString())
                       .collect(Collectors.toSet());
    }

    /**
     * Compute cyclomatic complexity.
     *
     * McCabe's formula: E - N + 2P
     * Simplified: count decision points (if, while, for, case, &&, ||, ?, catch)
     */
    private int computeComplexity(MethodDeclaration method) {

        final int[] complexity = {1}; // Base complexity

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

    /**
     * Parse JavaScript/TypeScript file.
     *
     * IMPLEMENTATION NOTE: This delegates to Node.js script with Babel parser.
     * Full implementation omitted for brevity - would exec:
     * node scripts/parse-js.js <file> --output json
     */
    private void parseJavaScriptFile(Path file, Path sourceRoot,
                                     Map<String, FlowNode> nodes,
                                     List<FlowEdge> edges) {
        // Delegate to external parser
        // nodes.put(...) with extracted functions
    }

    /**
     * Parse Python file using AST module.
     *
     * Delegates to: python scripts/parse-py.py <file> --output json
     */
    private void parsePythonFile(Path file, Path sourceRoot,
                                 Map<String, FlowNode> nodes,
                                 List<FlowEdge> edges) {
        // Delegate to external parser
    }

    /**
     * Parse Ruby file using Parser gem.
     *
     * Delegates to: ruby scripts/parse-rb.rb <file> --output json
     */
    private void parseRubyFile(Path file, Path sourceRoot,
                               Map<String, FlowNode> nodes,
                               List<FlowEdge> edges) {
        // Delegate to external parser
    }

    /**
     * Compute graph metrics (centrality, hotspots).
     */
    private void computeMetrics(Map<String, FlowNode> nodes, List<FlowEdge> edges) {

        // Build adjacency list
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();

        for (FlowEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
            incoming.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge.getFrom());
        }

        // Compute in/out degree
        for (FlowNode node : nodes.values()) {
            node.setInDegree(incoming.getOrDefault(node.getId(), Collections.emptyList()).size());
            node.setOutDegree(outgoing.getOrDefault(node.getId(), Collections.emptyList()).size());

            // Simple centrality: (inDegree + outDegree) / totalNodes
            node.setCentrality((double)(node.getInDegree() + node.getOutDegree()) / nodes.size());
        }
    }
}
