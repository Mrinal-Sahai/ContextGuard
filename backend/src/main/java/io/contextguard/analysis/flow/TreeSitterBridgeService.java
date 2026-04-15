 package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TREE-SITTER BRIDGE SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * HTTP client for the tree-sitter-bridge container.
 *
 * In the previous architecture this service spawned a Node.js subprocess
 * and communicated via newline-delimited JSON over stdin/stdout.  That has
 * been replaced with plain HTTP calls to the tree-sitter-bridge container
 * running tree-sitter-http-server.js on port 3000.
 *
 * Bridge endpoints used:
 *   GET  /health  → { status, tsc, pyright, goTypes }
 *   POST /parse   → { language, filePath, content } → { nodes, edges, error }
 *
 * Public API is unchanged so ASTParserService requires no edits.
 */
@Service
public class TreeSitterBridgeService {

    private static final Logger logger = LoggerFactory.getLogger(TreeSitterBridgeService.class);

    @Value("${bridge.tree-sitter.url:http://tree-sitter-bridge:3000}")
    private String bridgeUrl;

    @Value("${treesitter.bridge.timeout-ms:10000}")
    private long timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebClient webClient;

    public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "python", "go", "ruby", "javascript", "typescript"
    );

    // ── Lazy init ──────────────────────────────────────────────────────────────

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder()
                    .baseUrl(bridgeUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10 MB
                    .build();
        }
        return webClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse a source file via the tree-sitter bridge container.
     *
     * @param language  one of: python, go, ruby, javascript, typescript
     * @param filePath  path used as node ID prefix
     * @param content   raw source code
     * @return          parsed result containing nodes and edges
     */
    public TreeSitterResult parse(String language, String filePath, String content)
            throws Exception {

        Map<String, String> body = Map.of(
                "language", language,
                "filePath", filePath,
                "content",  content
        );

        JsonNode response;
        try {
            String json = client()
                    .post()
                    .uri("/parse")
                    .header("Content-Type", "application/json")
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            response = objectMapper.readTree(json);
        } catch (WebClientRequestException | WebClientResponseException e) {
            throw new TreeSitterUnavailableException(
                    "tree-sitter-bridge unreachable at " + bridgeUrl + ": " + e.getMessage(), e);
        }

        JsonNode errorNode = response.get("error");
        if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
            // JS bridge already prefixes "Tree-sitter error for <file>:" — don't double-wrap
            throw new TreeSitterParseException(errorNode.asText());
        }

        TreeSitterResult result = TreeSitterResult.fromJson(response);
        logger.debug("[tree-sitter] parsed {} → {} nodes, {} edges",
                filePath, result.nodes.size(), result.edges.size());
        return result;
    }

    /**
     * Returns true if the bridge container is reachable and healthy.
     */
    public boolean isAvailable() {
        try {
            String body = client()
                    .get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            JsonNode json = objectMapper.readTree(body);
            return "ok".equals(json.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT & EXCEPTION TYPES  (unchanged — ASTParserService depends on these)
    // ─────────────────────────────────────────────────────────────────────────

    public static class TreeSitterResult {

        public final List<ParsedNode> nodes;
        public final List<ParsedEdge> edges;

        public TreeSitterResult(List<ParsedNode> nodes, List<ParsedEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public static TreeSitterResult fromJson(JsonNode json) {
            List<ParsedNode> nodes = new ArrayList<>();
            List<ParsedEdge> edges = new ArrayList<>();

            JsonNode nodesArr = json.get("nodes");
            if (nodesArr != null && nodesArr.isArray())
                for (JsonNode n : nodesArr) nodes.add(ParsedNode.fromJson(n));

            JsonNode edgesArr = json.get("edges");
            if (edgesArr != null && edgesArr.isArray())
                for (JsonNode e : edgesArr) edges.add(ParsedEdge.fromJson(e));

            return new TreeSitterResult(nodes, edges);
        }

        public static class ParsedNode {
            public final String  id;
            public final String  label;
            public final String  filePath;
            public final int     startLine;
            public final int     endLine;
            public final String  returnType;
            public final int     cyclomaticComplexity;
            public final String  classContext;
            public final boolean isAsync;
            public final List<String> decorators;

            public ParsedNode(String id, String label, String filePath,
                              int startLine, int endLine, String returnType,
                              int cyclomaticComplexity, String classContext,
                              boolean isAsync, List<String> decorators) {
                this.id                   = id;
                this.label                = label;
                this.filePath             = filePath;
                this.startLine            = startLine;
                this.endLine              = endLine;
                this.returnType           = returnType;
                this.cyclomaticComplexity = cyclomaticComplexity;
                this.classContext         = classContext;
                this.isAsync              = isAsync;
                this.decorators           = decorators;
            }

            public static ParsedNode fromJson(JsonNode n) {
                List<String> deco = new ArrayList<>();
                JsonNode da = n.get("decorators");
                if (da != null && da.isArray()) for (JsonNode d : da) deco.add(d.asText());
                return new ParsedNode(
                        n.path("id").asText(),
                        n.path("label").asText(),
                        n.path("filePath").asText(),
                        n.path("startLine").asInt(0),
                        n.path("endLine").asInt(0),
                        n.path("returnType").asText("unknown"),
                        n.path("cyclomaticComplexity").asInt(1),
                        n.path("classContext").isNull() ? null : n.path("classContext").textValue(),
                        n.path("isAsync").asBoolean(false),
                        deco
                );
            }
        }

        public static class ParsedEdge {
            public final String from;
            public final String to;
            public final String callType;
            public final int    sourceLine;

            public ParsedEdge(String from, String to, String callType, int sourceLine) {
                this.from       = from;
                this.to         = to;
                this.callType   = callType;
                this.sourceLine = sourceLine;
            }

            public static ParsedEdge fromJson(JsonNode e) {
                return new ParsedEdge(
                        e.path("from").asText(),
                        e.path("to").asText(),
                        e.path("callType").asText("METHOD_CALL"),
                        e.path("sourceLine").asInt(0)
                );
            }
        }
    }

    public static class TreeSitterUnavailableException extends RuntimeException {
        public TreeSitterUnavailableException(String msg)              { super(msg); }
        public TreeSitterUnavailableException(String msg, Throwable t) { super(msg, t); }
    }

    public static class TreeSitterParseException extends Exception {
        public TreeSitterParseException(String msg)              { super(msg); }
        public TreeSitterParseException(String msg, Throwable t) { super(msg, t); }
    }
}
