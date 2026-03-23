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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LANGUAGE TOOL BRIDGE SERVICE
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Manages the Tier 2 language tool bridges:
 *   TypeScript → TypeScript Compiler API (via tree-sitter-bridge.js Tier 2)
 *   Python     → Pyright (via tree-sitter-bridge.js Tier 2)
 *   Go         → go/types binary (via tree-sitter-bridge.js Tier 2)
 *
 * HOW IT RELATES TO TreeSitterBridgeService
 * ───────────────────────────────────────────
 * TreeSitterBridgeService manages the Node.js process and the low-level
 * stdin/stdout protocol. This service sits on top of it and provides two
 * higher-level operations that TreeSitterBridgeService does not:
 *
 *  1. indexBatch()  — Pass 1: send an entire batch of files to the bridge
 *                     and populate a CrossFileSymbolIndex with the results.
 *                     This is a batch operation: one request per language
 *                     covering all files of that language at once.
 *
 *  2. parseFile()   — Pass 2: parse a single file with full type resolution.
 *                     Returns a ParseResult containing nodes and type-resolved
 *                     call edges. Falls back to Tree-sitter if Tier 2 fails.
 *
 * BATCH vs SINGLE
 * ────────────────
 * Batch (Pass 1) is important for TypeScript specifically. The TypeScript
 * Compiler API creates a "Program" that needs to know about all .ts files
 * to resolve cross-file imports. Sending files one at a time means each
 * file's Program only knows about itself — cross-file types won't resolve.
 *
 * For Python and Go, batch mode is used to populate the index efficiently
 * but single-file mode still produces good results for those languages since
 * Pyright and go/types operate file-by-file anyway.
 *
 * PROTOCOL EXTENSION
 * ───────────────────
 * This service uses two additional request types beyond the standard
 * TreeSitterBridgeService parse request:
 *
 *   "index_batch" request:
 *     { "id": "...", "type": "index_batch", "language": "typescript",
 *       "files": { "path/to/file.ts": "...content...", ... } }
 *
 *   Response:
 *     { "id": "...", "type": "index_batch",
 *       "symbols": [{ "nodeId": "...", "label": "...", "classContext": "...",
 *                     "filePath": "...", "language": "..." }],
 *       "importAliases": [{ "filePath": "...", "alias": "...", "canonical": "..." }],
 *       "variableTypes": [{ "filePath": "...", "varName": "...", "typeName": "..." }] }
 *
 * AVAILABILITY
 * ─────────────
 * Each language's Tier 2 capability is individually available/unavailable
 * depending on what was installed. isAvailable(language) returns false if:
 *   - The Node process is down
 *   - The tree-sitter-bridge.js reported the tool unavailable at startup
 *   - A previous batch request failed for this language
 *
 * CONFIGURATION (application.properties)
 * ────────────────────────────────────────
 *   treesitter.bridge.script-path   ← shared with TreeSitterBridgeService
 *   treesitter.bridge.timeout-ms    ← per-file timeout (default 10000)
 *   treesitter.batch.timeout-ms     ← batch index timeout (default 60000)
 */
@Service
public class LanguageToolBridgeService implements InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(LanguageToolBridgeService.class);

    // Injected via application.properties
    @Value("${treesitter.bridge.script-path:tree-sitter-bridge/tree-sitter-bridge.js}")
    private String scriptPath;

    @Value("${treesitter.bridge.node-command:node}")
    private String nodeCommand;

    @Value("${treesitter.bridge.timeout-ms:10000}")
    private long singleFileTimeoutMs;

    @Value("${treesitter.batch.timeout-ms:60000}")
    private long batchTimeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The Node process (shared with TreeSitterBridgeService conceptually, but
    // this service has its own process so it can use the extended protocol)
    private volatile Process        nodeProcess;
    private volatile BufferedWriter processWriter;
    private volatile BufferedReader processReader;
    private volatile boolean        permanentlyUnavailable = false;
    private volatile Thread         readerThread;

    private final AtomicLong                                         requestCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending    = new ConcurrentHashMap<>();
    private final Object                                             processLock    = new Object();

    // Per-language availability flags (updated when a tool reports unavailability)
    private final Map<String, Boolean> languageAvailability = new ConcurrentHashMap<>(Map.of(
            "typescript", true,
            "python",     true,
            "go",         true
    ));

    // ─────────────────────────────────────────────────────────────────────────
    // Spring lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void afterPropertiesSet() {
        try {
            startProcess();
            logger.info("LanguageToolBridgeService: process started (script={})", scriptPath);
        } catch (Exception e) {
            logger.warn("LanguageToolBridgeService: could not start process at startup — " +
                                "Tier 2 language tooling unavailable. {}", e.getMessage());
            permanentlyUnavailable = true;
        }
    }

    @Override
    public void destroy() {
        stopProcess();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if Tier 2 tooling is available for this language.
     * Falls back gracefully: if false, ASTParserService uses Tree-sitter instead.
     */
    public boolean isAvailable(String language) {
        if (permanentlyUnavailable) return false;
        if (nodeProcess == null || !nodeProcess.isAlive()) return false;
        return languageAvailability.getOrDefault(language, false);
    }

    /**
     * PASS 1 OPERATION: Index an entire batch of files for one language.
     *
     * Sends all files to the bridge in a single request. The bridge uses the
     * Tier 2 tool (tsc / Pyright / go/types) to extract symbol information
     * that is then registered into the CrossFileSymbolIndex.
     *
     * For TypeScript: the tsc compiler creates a multi-file Program, giving
     * accurate cross-file type resolution for all files in the batch.
     *
     * @param language     one of: "typescript", "python", "go"
     * @param files        map of filePath → source content
     * @param symbolIndex  the index to populate with extracted symbols
     * @return             true if the batch was handled successfully; false to trigger fallback
     */
    public boolean indexBatch(String language, Map<String, String> files,
                              CrossFileSymbolIndex symbolIndex) {
        if (!isAvailable(language)) return false;
        if (files.isEmpty()) return true;

        String requestId = String.valueOf(requestCounter.incrementAndGet());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, future);

        try {
            sendIndexBatchRequest(requestId, language, files);
        } catch (IOException e) {
            pending.remove(requestId);
            logger.warn("LanguageToolBridge: failed to send index_batch for {}: {}", language, e.getMessage());
            return false;
        }

        JsonNode response;
        try {
            response = future.get(batchTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(requestId);
            logger.warn("LanguageToolBridge: index_batch timeout for {} ({} files, {}ms)",
                    language, files.size(), batchTimeoutMs);
            // Mark language as unavailable after timeout — tool is too slow
            languageAvailability.put(language, false);
            return false;
        } catch (Exception e) {
            pending.remove(requestId);
            logger.warn("LanguageToolBridge: index_batch failed for {}: {}", language, e.getMessage());
            return false;
        }

        // Check for tool availability error from bridge
        JsonNode errorNode = response.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            String errorMsg = errorNode.asText();
            if (errorMsg.contains("not available") || errorMsg.contains("not found")) {
                logger.info("LanguageToolBridge: Tier 2 tool unavailable for {} — {}. " +
                                    "Falling back to Tree-sitter.", language, errorMsg);
                languageAvailability.put(language, false);
            } else {
                logger.warn("LanguageToolBridge: index_batch error for {}: {}", language, errorMsg);
            }
            return false;
        }

        // Populate the symbol index from the response
        populateSymbolIndex(response, symbolIndex);

        logger.info("LanguageToolBridge: indexed {} {} files → {} symbols",
                files.size(), language, countSymbols(response));
        return true;
    }

    /**
     * PASS 2 OPERATION: Parse a single file with full Tier 2 type resolution.
     *
     * @param language  one of: "typescript", "python", "go"
     * @param filePath  file path
     * @param content   source content
     * @return          ParseResult with nodes + type-resolved edges, or null if failed
     */
    public ParseResult parseFile(String language, String filePath, String content) {
        if (!isAvailable(language)) return null;

        String requestId = String.valueOf(requestCounter.incrementAndGet());
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(requestId, future);

        try {
            sendParseRequest(requestId, language, filePath, content);
        } catch (IOException e) {
            pending.remove(requestId);
            logger.warn("LanguageToolBridge: failed to send parse request for {}: {}", filePath, e.getMessage());
            return null;
        }

        JsonNode response;
        try {
            response = future.get(singleFileTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(requestId);
            logger.warn("LanguageToolBridge: parse timeout for {} ({}ms)", filePath, singleFileTimeoutMs);
            return null;
        } catch (Exception e) {
            pending.remove(requestId);
            logger.warn("LanguageToolBridge: parse error for {}: {}", filePath, e.getMessage());
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
        // Register all discovered symbols
        JsonNode symbols = response.get("symbols");
        if (symbols != null && symbols.isArray()) {
            for (JsonNode sym : symbols) {
                String nodeId      = sym.path("nodeId").asText(null);
                String label       = sym.path("label").asText(null);
                String classCtx    = sym.path("classContext").isNull() ? null : sym.path("classContext").asText(null);
                String filePath    = sym.path("filePath").asText(null);
                String language    = sym.path("language").asText("unknown");

                if (nodeId != null && label != null && filePath != null) {
                    if (classCtx != null) {
                        symbolIndex.registerClass(classCtx, classCtx, filePath, language);
                        symbolIndex.registerMethod(label, nodeId, classCtx, filePath, language);
                    } else {
                        symbolIndex.registerMethod(label, nodeId, null, filePath, language);
                    }
                }
            }
        }

        // Register import aliases
        JsonNode aliases = response.get("importAliases");
        if (aliases != null && aliases.isArray()) {
            for (JsonNode alias : aliases) {
                String filePath  = alias.path("filePath").asText(null);
                String aliasName = alias.path("alias").asText(null);
                String canonical = alias.path("canonical").asText(null);
                if (filePath != null && aliasName != null && canonical != null) {
                    symbolIndex.registerImportAlias(filePath, aliasName, canonical);
                }
            }
        }

        // Register variable types (constructor assignments, typed declarations)
        JsonNode varTypes = response.get("variableTypes");
        if (varTypes != null && varTypes.isArray()) {
            for (JsonNode vt : varTypes) {
                String filePath  = vt.path("filePath").asText(null);
                String varName   = vt.path("varName").asText(null);
                String typeName  = vt.path("typeName").asText(null);
                if (filePath != null && varName != null && typeName != null) {
                    symbolIndex.registerVariableType(filePath, varName, typeName);
                }
            }
        }
    }

    private int countSymbols(JsonNode response) {
        JsonNode symbols = response.get("symbols");
        return (symbols != null && symbols.isArray()) ? symbols.size() : 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROCESS I/O
    // ─────────────────────────────────────────────────────────────────────────

    private void sendIndexBatchRequest(String id, String language,
                                       Map<String, String> files) throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("id", id);
        request.put("type", "index_batch");
        request.put("language", language);

        ObjectNode filesNode = objectMapper.createObjectNode();
        files.forEach(filesNode::put);
        request.set("files", filesNode);

        synchronized (processWriter) {
            processWriter.write(objectMapper.writeValueAsString(request));
            processWriter.newLine();
            processWriter.flush();
        }
    }

    private void sendParseRequest(String id, String language,
                                  String filePath, String content) throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("id", id);
        request.put("type", "parse");
        request.put("language", language);
        request.put("filePath", filePath);
        request.put("content", content);

        synchronized (processWriter) {
            processWriter.write(objectMapper.writeValueAsString(request));
            processWriter.newLine();
            processWriter.flush();
        }
    }

    private void readResponseLoop() {
        try {
            String line;
            while ((line = processReader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode response = objectMapper.readTree(line);
                    String id = response.path("id").asText(null);
                    if (id == null) continue;
                    CompletableFuture<JsonNode> future = pending.remove(id);
                    if (future != null) future.complete(response);
                } catch (Exception e) {
                    logger.warn("LanguageToolBridge: failed to parse response: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                logger.error("LanguageToolBridge: reader thread IOException — process died: {}", e.getMessage());
                permanentlyUnavailable = true;
            }
        }
        // Fail all pending futures
        pending.forEach((id, f) ->
                                f.completeExceptionally(new RuntimeException("Bridge process died")));
        pending.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROCESS LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    private void startProcess() throws Exception {
        File script = resolveScript();
        if (!script.exists()) {
            throw new RuntimeException("tree-sitter-bridge.js not found at: " + script.getAbsolutePath());
        }

        ProcessBuilder pb = new ProcessBuilder(nodeCommand, script.getAbsolutePath());
        pb.redirectErrorStream(false);
        nodeProcess   = pb.start();
        processWriter = new BufferedWriter(new OutputStreamWriter(
                nodeProcess.getOutputStream(), StandardCharsets.UTF_8));
        processReader = new BufferedReader(new InputStreamReader(
                nodeProcess.getInputStream(), StandardCharsets.UTF_8));

        // Drain stderr → warn log
        Thread stderr = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(
                    nodeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = err.readLine()) != null) {
                    logger.warn("[lang-tool-bridge stderr] {}", l);
                }
            } catch (IOException ignored) {}
        }, "lang-tool-bridge-stderr");
        stderr.setDaemon(true);
        stderr.start();

        readerThread = new Thread(this::readResponseLoop, "lang-tool-bridge-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        Thread.sleep(1000);  // 1s: enough for Node to load tree-sitter grammars on cold start
        if (!nodeProcess.isAlive()) {
            throw new RuntimeException("Node process exited immediately — check Node.js installation.");
        }
    }

    private void stopProcess() {
        pending.values().forEach(f -> f.completeExceptionally(new RuntimeException("Shutting down")));
        pending.clear();
        if (nodeProcess != null) {
            try { if (processWriter != null) processWriter.close(); } catch (IOException ignored) {}
            nodeProcess.destroyForcibly();
            nodeProcess = null;
        }
        if (readerThread != null) { readerThread.interrupt(); readerThread = null; }
    }

    private File resolveScript() {
        File script = new File(scriptPath);
        if (!script.exists()) script = new File(System.getProperty("user.dir"), scriptPath);
        return script;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESULT TYPE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of a Tier 2 single-file parse.
     * Mirrors TreeSitterBridgeService.TreeSitterResult so ASTParserService
     * can use the same mapBridgeResultToGraph() path for both.
     */
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

            JsonNode nodesArr = json.get("nodes");
            if (nodesArr != null && nodesArr.isArray()) {
                for (JsonNode n : nodesArr) {
                    nodes.add(TreeSitterBridgeService.TreeSitterResult.ParsedNode.fromJson(n));
                }
            }
            JsonNode edgesArr = json.get("edges");
            if (edgesArr != null && edgesArr.isArray()) {
                for (JsonNode e : edgesArr) {
                    edges.add(TreeSitterBridgeService.TreeSitterResult.ParsedEdge.fromJson(e));
                }
            }
            return new ParseResult(nodes, edges);
        }
    }
}
