package io.contextguard.analysis.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * DART ANALYSIS BRIDGE SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * HTTP client for the dart-bridge container (dart-bridge-server.js on port 3001).
 *
 * In the previous architecture this service owned the Dart Analysis Server
 * process directly — spawning it via ProcessBuilder, performing the LSP
 * handshake, and sending textDocument/definition requests over stdio.
 * That is now handled entirely inside the dart-bridge container.
 *
 * This service becomes a thin HTTP client with two responsibilities:
 *
 *  1. indexBatch()  — POST /index-batch → symbol extraction (regex-based in
 *                     the Node.js server, mirrors the old Java regex logic).
 *                     Populates the CrossFileSymbolIndex.
 *
 *  2. parseFile()   — POST /analyze → tree-sitter node extraction + LSP edge
 *                     resolution via the Dart Analysis Server.
 *                     The response contains raw edges { from, toFile, toLine }.
 *                     This service resolves toFile+toLine → nodeId using the
 *                     CrossFileSymbolIndex (symbolIndex.resolveByLocation).
 *
 * Graceful degradation is unchanged: if the dart-bridge container is down,
 * both methods return false/null and ASTParserService falls back to the
 * tree-sitter-bridge /parse endpoint for Dart files.
 *
 * Configuration:
 *   bridge.dart.url                       — dart-bridge container URL
 *   dart.analysis.timeout-ms              — per-file timeout
 *   dart.analysis.batch-timeout-ms        — index-batch timeout
 *   dart.analysis.max-calls-per-file      — passed to bridge in request
 */
@Service
public class DartAnalysisBridgeService {

    private static final Logger logger = LoggerFactory.getLogger(DartAnalysisBridgeService.class);

    @Value("${bridge.dart.url:http://dart-bridge:3001}")
    private String bridgeUrl;

    @Value("${dart.analysis.timeout-ms:15000}")
    private long analysisTimeoutMs;

    @Value("${dart.analysis.batch-timeout-ms:120000}")
    private long batchTimeoutMs;

    @Value("${dart.analysis.max-calls-per-file:200}")
    private int maxCallsPerFile;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebClient webClient;

    private volatile Boolean available = null;  // null = not yet checked

    // ── Lazy init ──────────────────────────────────────────────────────────────

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder()
                    .baseUrl(bridgeUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
        }
        return webClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    public boolean isAvailable() {
        if (available != null) return available;
        available = checkHealth();
        return available;
    }

    /**
     * PASS 1: Index a batch of Dart files into the CrossFileSymbolIndex.
     *
     * @return true if indexing succeeded; false triggers Tree-sitter fallback
     */
    public boolean indexBatch(Map<String, String> files, CrossFileSymbolIndex symbolIndex) {
        if (!isAvailable() || files.isEmpty()) return false;

        Map<String, Object> body = Map.of("files", files);
        JsonNode response;
        try {
            String json = client()
                    .post()
                    .uri("/index-batch")
                    .header("Content-Type", "application/json")
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(batchTimeoutMs))
                    .block();
            response = objectMapper.readTree(json);
        } catch (Exception e) {
            logger.warn("DartAnalysisBridge: index-batch HTTP error — {}", e.getMessage());
            return false;
        }

        JsonNode errorNode = response.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            logger.warn("DartAnalysisBridge: index-batch error — {}", errorNode.asText());
            return false;
        }

        populateSymbolIndex(response, symbolIndex);
        logger.info("DartAnalysisBridge: indexed {} Dart files → {} symbols",
                files.size(), countSymbols(response));
        return true;
    }

    /**
     * PASS 2: Parse a single Dart file with full type resolution.
     *
     * @return populated DartParseResult, or null to trigger Tree-sitter fallback
     */
    public DartParseResult parseFile(String filePath, String content,
                                     CrossFileSymbolIndex symbolIndex) {
        if (!isAvailable()) return null;

        Map<String, Object> body = Map.of(
                "filePath",        filePath,
                "content",         content,
                "maxCallsPerFile", maxCallsPerFile
        );

        JsonNode response;
        try {
            String json = client()
                    .post()
                    .uri("/analyze")
                    .header("Content-Type", "application/json")
                    .bodyValue(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(analysisTimeoutMs))
                    .block();
            response = objectMapper.readTree(json);
        } catch (Exception e) {
            logger.warn("DartAnalysisBridge: analyze HTTP error for {} — {}", filePath, e.getMessage());
            return null;
        }

        JsonNode errorNode = response.get("error");
        if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
            logger.warn("DartAnalysisBridge: analyze error for {} — {}", filePath, errorNode.asText());
            return null;
        }

        return buildParseResult(response, symbolIndex);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESPONSE MAPPING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Convert the bridge response into DartParseResult.
     *
     * Nodes are returned verbatim from the bridge (tree-sitter extraction).
     * Edges are raw { from, toFile, toLine } — we resolve toFile+toLine to a
     * node ID via CrossFileSymbolIndex.resolveByLocation(), which maps
     * declaration locations to the node registered in Pass 1.
     */
    private DartParseResult buildParseResult(JsonNode response, CrossFileSymbolIndex symbolIndex) {
        List<DartNode> nodes = new ArrayList<>();
        List<DartEdge> edges = new ArrayList<>();

        JsonNode nodesArr = response.get("nodes");
        if (nodesArr != null && nodesArr.isArray()) {
            for (JsonNode n : nodesArr) {
                nodes.add(new DartNode(
                        n.path("id").asText(),
                        n.path("label").asText(),
                        n.path("filePath").asText(),
                        n.path("startLine").asInt(0),
                        n.path("endLine").asInt(0),
                        n.path("returnType").asText("void"),
                        n.path("cyclomaticComplexity").asInt(1),
                        n.path("classContext").isNull() ? null : n.path("classContext").textValue(),
                        n.path("isAsync").asBoolean(false)
                ));
            }
        }

        JsonNode edgesArr = response.get("edges");
        if (edgesArr != null && edgesArr.isArray()) {
            for (JsonNode e : edgesArr) {
                String from     = e.path("from").textValue();
                String toFile   = e.path("toFile").textValue();
                int    toLine   = e.path("toLine").asInt(0);
                int    srcLine  = e.path("sourceLine").asInt(0);

                if (from == null || toFile == null) continue;

                // Resolve raw location → node ID via the symbol index
                String to = symbolIndex.resolveByLocation(toFile, toLine);
                if (to != null && !to.equals(from)) {
                    edges.add(new DartEdge(from, to, srcLine));
                }
            }
        }

        logger.debug("DartAnalysisBridge: {} nodes, {} edges from bridge response",
                nodes.size(), edges.size());
        return new DartParseResult(nodes, edges);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SYMBOL INDEX POPULATION
    // ─────────────────────────────────────────────────────────────────────────

    private void populateSymbolIndex(JsonNode response, CrossFileSymbolIndex symbolIndex) {
        JsonNode symbols = response.get("symbols");
        if (symbols != null && symbols.isArray()) {
            for (JsonNode sym : symbols) {
                String nodeId   = sym.path("nodeId").textValue();
                String label    = sym.path("label").textValue();
                String classCtx = sym.path("classContext").isNull() ? null : sym.path("classContext").textValue();
                String filePath = sym.path("filePath").textValue();
                if (nodeId != null && label != null && filePath != null) {
                    if (classCtx != null) {
                        symbolIndex.registerClass(classCtx, classCtx, filePath, "dart");
                        symbolIndex.registerMethod(label, nodeId, classCtx, filePath, "dart");
                    } else {
                        symbolIndex.registerMethod(label, nodeId, null, filePath, "dart");
                    }
                }
            }
        }

        JsonNode aliases = response.get("importAliases");
        if (aliases != null && aliases.isArray()) {
            for (JsonNode a : aliases) {
                String fp  = a.path("filePath").textValue();
                String al  = a.path("alias").textValue();
                String can = a.path("canonical").textValue();
                if (fp != null && al != null && can != null)
                    symbolIndex.registerImportAlias(fp, al, can);
            }
        }

        JsonNode varTypes = response.get("variableTypes");
        if (varTypes != null && varTypes.isArray()) {
            for (JsonNode vt : varTypes) {
                String fp  = vt.path("filePath").textValue();
                String vn  = vt.path("varName").textValue();
                String tn  = vt.path("typeName").textValue();
                if (fp != null && vn != null && tn != null)
                    symbolIndex.registerVariableType(fp, vn, tn);
            }
        }
    }

    private int countSymbols(JsonNode response) {
        JsonNode s = response.get("symbols");
        return (s != null && s.isArray()) ? s.size() : 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HEALTH CHECK
    // ─────────────────────────────────────────────────────────────────────────

    private boolean checkHealth() {
        try {
            String body = client()
                    .get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            JsonNode h = objectMapper.readTree(body);
            boolean ok = "ok".equals(h.path("status").asText());
            if (ok) {
                logger.info("DartAnalysisBridge: available (lsp={}, grammar={})",
                        h.path("dartAvailable").asBoolean(),
                        h.path("dartGrammar").asBoolean());
            }
            return ok;
        } catch (WebClientRequestException e) {
            logger.warn("DartAnalysisBridge: dart-bridge container unreachable at {} — " +
                                "Dart analysis disabled. {}", bridgeUrl, e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warn("DartAnalysisBridge: health check failed — {}. Dart analysis disabled.", e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT TYPES  (same as before — ASTParserService depends on these)
    // ─────────────────────────────────────────────────────────────────────────

    public record DartNode(String id, String label, String filePath, int startLine, int endLine, String returnType,
                           int cyclomaticComplexity, String classContext, boolean isAsync) {
    }

    public record DartEdge(String from, String to, int sourceLine) {
    }

    public record DartParseResult(List<DartNode> nodes, List<DartEdge> edges) {
    }
}
