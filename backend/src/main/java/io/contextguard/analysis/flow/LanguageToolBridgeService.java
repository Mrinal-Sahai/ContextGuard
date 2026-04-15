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
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LANGUAGE TOOL BRIDGE SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * HTTP client for the tree-sitter-bridge container — Tier 2 operations.
 *
 * In the previous architecture this service spawned its own Node.js process
 * and used an extended newline-delimited JSON protocol. That has been replaced
 * with plain HTTP calls to the same tree-sitter-bridge container.
 *
 * Bridge endpoints used:
 *   GET  /health       → { tsc, pyright, goTypes }
 *   POST /index-batch  → { language, files } → { symbols, importAliases, variableTypes }
 *   POST /parse        → { language, filePath, content } → { nodes, edges }
 *
 * Public API is unchanged so ASTParserService requires no edits.
 */
@Service
public class LanguageToolBridgeService {

    private static final Logger logger = LoggerFactory.getLogger(LanguageToolBridgeService.class);

    @Value("${bridge.tree-sitter.url:http://tree-sitter-bridge:3000}")
    private String bridgeUrl;

    @Value("${treesitter.bridge.timeout-ms:10000}")
    private long singleFileTimeoutMs;

    @Value("${treesitter.batch.timeout-ms:60000}")
    private long batchTimeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebClient webClient;

    // Per-language Tier 2 availability (populated from /health on first use)
    private final Map<String, Boolean> languageAvailability = new ConcurrentHashMap<>(Map.of(
            "typescript", true,
            "python",     true,
            "go",         true
    ));

    private volatile boolean healthChecked = false;

    // ── Lazy init ──────────────────────────────────────────────────────────────

    private WebClient client() {
        if (webClient == null) {
            webClient = WebClient.builder()
                    .baseUrl(bridgeUrl)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50 MB
                    .build();
        }
        return webClient;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if Tier 2 tooling is available for this language.
     */
    public boolean isAvailable(String language) {
        ensureHealthChecked();
        return languageAvailability.getOrDefault(language, false);
    }

    /**
     * PASS 1: Index an entire batch of files for one language.
     * Populates the CrossFileSymbolIndex with symbols, import aliases, and variable types.
     */
    public boolean indexBatch(String language, Map<String, String> files,
                              CrossFileSymbolIndex symbolIndex) {
        if (!isAvailable(language)) return false;
        if (files.isEmpty()) return true;

        Map<String, Object> body = Map.of("language", language, "files", files);

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
            logger.warn("LanguageToolBridge: index_batch HTTP error for {}: {}", language, e.toString(), e);
            languageAvailability.put(language, false);
            return false;
        }

        JsonNode errorNode = response.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            String msg = errorNode.asText();
            if (msg.contains("not available") || msg.contains("not found")) {
                logger.info("LanguageToolBridge: Tier 2 tool unavailable for {} — {}. Falling back to Tree-sitter.", language, msg);
                languageAvailability.put(language, false);
            } else {
                logger.warn("LanguageToolBridge: index_batch error for {}: {}", language, msg);
            }
            return false;
        }

        populateSymbolIndex(response, symbolIndex);
        logger.info("LanguageToolBridge: indexed {} {} files → {} symbols",
                files.size(), language, countSymbols(response));
        return true;
    }

    /**
     * PASS 2: Parse a single file with Tier 2 type resolution.
     */
    public ParseResult parseFile(String language, String filePath, String content) {
        if (!isAvailable(language)) return null;

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
                    .timeout(Duration.ofMillis(singleFileTimeoutMs))
                    .block();
            response = objectMapper.readTree(json);
        } catch (Exception e) {
            logger.warn("LanguageToolBridge: parse HTTP error for {}: {}", filePath, e.getMessage());
            return null;
        }

        JsonNode errorNode = response.get("error");
        if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
            logger.warn("LanguageToolBridge: parse error for {} ({}): {}", filePath, language, errorNode.asText());
            return null;
        }

        return ParseResult.fromJson(response);
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
                String lang     = sym.path("language").asText("unknown");
                if (nodeId != null && label != null && filePath != null) {
                    if (classCtx != null) {
                        symbolIndex.registerClass(classCtx, classCtx, filePath, lang);
                        symbolIndex.registerMethod(label, nodeId, classCtx, filePath, lang);
                    } else {
                        symbolIndex.registerMethod(label, nodeId, null, filePath, lang);
                    }
                }
            }
        }

        JsonNode aliases = response.get("importAliases");
        if (aliases != null && aliases.isArray()) {
            for (JsonNode alias : aliases) {
                String fp        = alias.path("filePath").textValue();
                String aliasName = alias.path("alias").textValue();
                String canonical = alias.path("canonical").textValue();
                if (fp != null && aliasName != null && canonical != null)
                    symbolIndex.registerImportAlias(fp, aliasName, canonical);
            }
        }

        JsonNode varTypes = response.get("variableTypes");
        if (varTypes != null && varTypes.isArray()) {
            for (JsonNode vt : varTypes) {
                String fp       = vt.path("filePath").textValue();
                String varName  = vt.path("varName").textValue();
                String typeName = vt.path("typeName").textValue();
                if (fp != null && varName != null && typeName != null)
                    symbolIndex.registerVariableType(fp, varName, typeName);
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

    private synchronized void ensureHealthChecked() {
        if (healthChecked) return;
        try {
            String body = client()
                    .get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            JsonNode h = objectMapper.readTree(body);
            // tsc controls typescript, pyright → python, goTypes → go
            if (!h.path("tsc").asBoolean(true))      languageAvailability.put("typescript", false);
            if (!h.path("pyright").asBoolean(true))  languageAvailability.put("python",     false);
            if (!h.path("goTypes").asBoolean(true))  languageAvailability.put("go",         false);
            logger.info("LanguageToolBridge: Tier 2 capabilities — tsc={}, pyright={}, go-types={}",
                    h.path("tsc").asBoolean(), h.path("pyright").asBoolean(), h.path("goTypes").asBoolean());
            healthChecked = true;
        } catch (WebClientRequestException e) {
            logger.warn("LanguageToolBridge: bridge unreachable at {} — Tier 2 disabled. {}", bridgeUrl, e.getMessage());
            languageAvailability.replaceAll((k, v) -> false);
            healthChecked = true;
        } catch (Exception e) {
            logger.warn("LanguageToolBridge: health check failed — {}. Tier 2 will be attempted per-language.", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT TYPE  (unchanged — ASTParserService depends on this)
    // ─────────────────────────────────────────────────────────────────────────

    public static class ParseResult {

        public final List<TreeSitterBridgeService.TreeSitterResult.ParsedNode> nodes;
        public final List<TreeSitterBridgeService.TreeSitterResult.ParsedEdge> edges;

        public ParseResult(List<TreeSitterBridgeService.TreeSitterResult.ParsedNode> nodes,
                           List<TreeSitterBridgeService.TreeSitterResult.ParsedEdge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public static ParseResult fromJson(JsonNode json) {
            List<TreeSitterBridgeService.TreeSitterResult.ParsedNode> nodes = new ArrayList<>();
            List<TreeSitterBridgeService.TreeSitterResult.ParsedEdge> edges = new ArrayList<>();

            JsonNode na = json.get("nodes");
            if (na != null && na.isArray())
                for (JsonNode n : na)
                    nodes.add(TreeSitterBridgeService.TreeSitterResult.ParsedNode.fromJson(n));

            JsonNode ea = json.get("edges");
            if (ea != null && ea.isArray())
                for (JsonNode e : ea)
                    edges.add(TreeSitterBridgeService.TreeSitterResult.ParsedEdge.fromJson(e));

            return new ParseResult(nodes, edges);
        }
    }
}
