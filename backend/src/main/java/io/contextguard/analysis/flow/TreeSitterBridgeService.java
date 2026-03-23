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
  * TREE-SITTER BRIDGE SERVICE
  * ═══════════════════════════════════════════════════════════════════════════════
  *
  * Manages a single persistent Node.js process running tree-sitter-bridge.js.
  * All non-Java language parsing is delegated here.
  *
  * Protocol:
  *   Request  → stdout of this JVM → stdin  of Node process (newline-delimited JSON)
  *   Response → stdout of Node process → stdin of this JVM (newline-delimited JSON)
  *
  * Each request carries a unique ID so concurrent callers can match responses.
  * The Node process is single-threaded but responds very quickly (tree-sitter
  * parses typical source files in < 5ms), so throughput is limited by
  * ASTParserService's thread pool concurrency, not by this bridge.
  *
  * LIFECYCLE:
  *   - Process is started lazily on first parse request.
  *   - On any process crash, it is restarted up to MAX_RESTART_ATTEMPTS times
  *     before being marked permanently unavailable for this JVM lifetime.
  *   - Spring lifecycle (InitializingBean / DisposableBean) ensures clean shutdown.
  *
  * CONFIGURATION (application.properties):
  *   treesitter.bridge.script-path   = path/to/tree-sitter-bridge.js
  *   treesitter.bridge.node-command  = node          (override for nvm, nodenv, etc.)
  *   treesitter.bridge.timeout-ms    = 10000         (per-file parse timeout)
  */
 @Service
 public class TreeSitterBridgeService implements InitializingBean, DisposableBean {

     private static final Logger logger = LoggerFactory.getLogger(TreeSitterBridgeService.class);

     private static final int MAX_RESTART_ATTEMPTS = 3;
     private static final int STARTUP_TIMEOUT_MS   = 10_000;

     @Value("${treesitter.bridge.script-path:tree-sitter-bridge/tree-sitter-bridge.js}")
     private String scriptPath;

     @Value("${treesitter.bridge.node-command:node}")
     private String nodeCommand;

     @Value("${treesitter.bridge.timeout-ms:10000}")
     private long timeoutMs;

     private final ObjectMapper objectMapper = new ObjectMapper();

     // ── Process state ──────────────────────────────────────────────────────────

     private volatile Process             nodeProcess;
     private volatile BufferedWriter      processWriter;
     private volatile BufferedReader      processReader;
     private volatile boolean             permanentlyUnavailable = false;
     private volatile int                 restartCount           = 0;

     // Pending requests: requestId → CompletableFuture waiting for the response
     private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests
             = new ConcurrentHashMap<>();

     // Reader thread that pumps stdout of the Node process into waiting futures
     private volatile Thread readerThread;

     // Monotonically increasing request ID
     private final AtomicLong requestCounter = new AtomicLong(0);

     // Guards process start/restart to prevent multiple threads racing
     private final Object processLock = new Object();

     // ── Supported languages ────────────────────────────────────────────────────

     public static final Set<String> SUPPORTED_LANGUAGES = Set.of(
             "python", "go", "ruby", "javascript", "typescript"
     );

     // ─────────────────────────────────────────────────────────────────────────
     // Spring lifecycle
     // ─────────────────────────────────────────────────────────────────────────

     @Override
     public void afterPropertiesSet() {
         // Eagerly start the Node process at Spring startup so the first request
         // doesn't pay the startup cost. If Node is unavailable, log a clear error.
         try {
             startNodeProcess();
             logger.info("TreeSitterBridgeService: Node process started successfully (script={})", scriptPath);
         } catch (Exception e) {
             logger.warn("TreeSitterBridgeService: Could not start Node process at startup — " +
                                 "non-Java language parsing will be unavailable until Node is reachable. " +
                                 "Error: {}", e.getMessage());
         }
     }

     @Override
     public void destroy() {
         logger.info("TreeSitterBridgeService: shutting down Node process");
         stopNodeProcess();
     }

     // ─────────────────────────────────────────────────────────────────────────
     // PUBLIC API
     // ─────────────────────────────────────────────────────────────────────────

     /**
      * Parse a source file using the tree-sitter Node bridge.
      *
      * @param language  one of: python, go, ruby, javascript, typescript
      * @param filePath  path used as node ID prefix (same as ASTParserService convention)
      * @param content   raw source code
      * @return          parsed result containing nodes and edges
      * @throws TreeSitterUnavailableException if Node process is not available
      * @throws TreeSitterParseException       if the bridge returns a parse error
      * @throws TimeoutException               if the parse exceeds timeoutMs
      */
     public TreeSitterResult parse(String language, String filePath, String content)
             throws Exception {

         if (permanentlyUnavailable) {
             throw new TreeSitterUnavailableException(
                     "Node process permanently unavailable after " + MAX_RESTART_ATTEMPTS + " restart attempts");
         }

         ensureProcessRunning();

         String requestId = String.valueOf(requestCounter.incrementAndGet());
         CompletableFuture<JsonNode> future = new CompletableFuture<>();
         pendingRequests.put(requestId, future);

         try {
             sendRequest(requestId, language, filePath, content);
         } catch (IOException e) {
             pendingRequests.remove(requestId);
             handleProcessDeath("write failed: " + e.getMessage());
             throw new TreeSitterUnavailableException("Could not write to Node process: " + e.getMessage(), e);
         }

         JsonNode response;
         try {
             response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
         } catch (TimeoutException e) {
             pendingRequests.remove(requestId);
             logger.warn("TreeSitter parse timeout for {} after {}ms", filePath, timeoutMs);
             throw e;
         } catch (ExecutionException e) {
             pendingRequests.remove(requestId);
             throw new TreeSitterParseException("Parse future failed for " + filePath, e.getCause());
         }

         // Check for error in response
         JsonNode errorNode = response.get("error");
         if (errorNode != null && !errorNode.isNull() && !errorNode.asText().isBlank()) {
             throw new TreeSitterParseException("Tree-sitter error for " + filePath + ": " + errorNode.asText());
         }

         return TreeSitterResult.fromJson(response);
     }

     /**
      * Returns true if the bridge is currently running and available.
      * Useful for health checks.
      */
     public boolean isAvailable() {
         return !permanentlyUnavailable && nodeProcess != null && nodeProcess.isAlive();
     }

     // ─────────────────────────────────────────────────────────────────────────
     // PROCESS MANAGEMENT
     // ─────────────────────────────────────────────────────────────────────────

     private void ensureProcessRunning() throws Exception {
         if (nodeProcess != null && nodeProcess.isAlive()) return;
         synchronized (processLock) {
             if (nodeProcess != null && nodeProcess.isAlive()) return;
             if (permanentlyUnavailable) {
                 throw new TreeSitterUnavailableException("Node process permanently unavailable");
             }
             startNodeProcess();
         }
     }

     private void startNodeProcess() throws Exception {
         File script = new File(scriptPath);
         if (!script.exists()) {
             // Try relative to working directory
             script = new File(System.getProperty("user.dir"), scriptPath);
         }
         if (!script.exists()) {
             throw new TreeSitterUnavailableException(
                     "tree-sitter-bridge.js not found at: " + scriptPath +
                             ". Run: npm install inside the tree-sitter-bridge directory.");
         }

         ProcessBuilder pb = new ProcessBuilder(nodeCommand, script.getAbsolutePath());
         pb.redirectErrorStream(false); // keep stderr separate (logs to our logger)

         nodeProcess    = pb.start();
         processWriter  = new BufferedWriter(new OutputStreamWriter(nodeProcess.getOutputStream(), StandardCharsets.UTF_8));
         processReader  = new BufferedReader(new InputStreamReader(nodeProcess.getInputStream(), StandardCharsets.UTF_8));

         // Drain stderr to our logger so tree-sitter warnings (grammar loading failures, etc.) are visible
         Thread stderrThread = new Thread(() -> {
             try (BufferedReader err = new BufferedReader(
                     new InputStreamReader(nodeProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                 String line;
                 while ((line = err.readLine()) != null) {
                     logger.warn("[tree-sitter-bridge stderr] {}", line);
                 }
             } catch (IOException e) {
                 // Process died — normal during shutdown
             }
         }, "tree-sitter-stderr");
         stderrThread.setDaemon(true);
         stderrThread.start();

         // Start the response reader thread
         readerThread = new Thread(this::readResponseLoop, "tree-sitter-reader");
         readerThread.setDaemon(true);
         readerThread.start();

         // Verify the process is alive after a short delay
         Thread.sleep(200);
         if (!nodeProcess.isAlive()) {
             throw new TreeSitterUnavailableException(
                     "Node process exited immediately. Check Node.js installation and npm install in " + scriptPath);
         }
     }

     private void stopNodeProcess() {
         // Cancel all pending futures
         pendingRequests.values().forEach(f ->
                                                  f.completeExceptionally(new TreeSitterUnavailableException("Bridge shutting down")));
         pendingRequests.clear();

         if (nodeProcess != null) {
             try {
                 if (processWriter != null) processWriter.close();
             } catch (IOException ignored) {}
             nodeProcess.destroyForcibly();
             nodeProcess = null;
         }
         if (readerThread != null) {
             readerThread.interrupt();
             readerThread = null;
         }
     }

     private void handleProcessDeath(String reason) {
         logger.error("TreeSitterBridge: Node process died — {}. Restart attempt {}/{}",
                 reason, restartCount + 1, MAX_RESTART_ATTEMPTS);

         synchronized (processLock) {
             stopNodeProcess();
             if (restartCount >= MAX_RESTART_ATTEMPTS) {
                 permanentlyUnavailable = true;
                 logger.error("TreeSitterBridge: exceeded {} restart attempts. " +
                                      "Non-Java parsing permanently disabled for this JVM run.", MAX_RESTART_ATTEMPTS);
                 return;
             }
             restartCount++;
             try {
                 startNodeProcess();
                 logger.info("TreeSitterBridge: Node process restarted successfully (attempt {})", restartCount);
             } catch (Exception e) {
                 logger.error("TreeSitterBridge: restart attempt {} failed: {}", restartCount, e.getMessage());
                 if (restartCount >= MAX_RESTART_ATTEMPTS) {
                     permanentlyUnavailable = true;
                 }
             }
         }
     }

     // ─────────────────────────────────────────────────────────────────────────
     // I/O
     // ─────────────────────────────────────────────────────────────────────────

     private void sendRequest(String id, String language, String filePath, String content)
             throws IOException {
         ObjectNode request = objectMapper.createObjectNode();
         request.put("id", id);
         request.put("language", language);
         request.put("filePath", filePath);
         request.put("content", content);
         String json = objectMapper.writeValueAsString(request);
         synchronized (processWriter) {
             processWriter.write(json);
             processWriter.newLine();
             processWriter.flush();
         }
     }

     /**
      * Runs on its own thread, reading newline-delimited JSON responses from
      * the Node process and routing each to the waiting CompletableFuture.
      */
     private void readResponseLoop() {
         try {
             String line;
             while ((line = processReader.readLine()) != null) {
                 if (line.isBlank()) continue;
                 try {
                     JsonNode response = objectMapper.readTree(line);
                     JsonNode idNode   = response.get("id");
                     if (idNode == null) continue;
                     String id = idNode.asText();
                     CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                     if (future != null) {
                         future.complete(response);
                     } else {
                         logger.warn("TreeSitterBridge: received response for unknown id={}", id);
                     }
                 } catch (Exception e) {
                     logger.warn("TreeSitterBridge: failed to parse response JSON: {} — line: {}",
                             e.getMessage(), line.length() > 200 ? line.substring(0, 200) : line);
                 }
             }
         } catch (IOException e) {
             if (!Thread.currentThread().isInterrupted()) {
                 handleProcessDeath("reader thread IOException: " + e.getMessage());
             }
         }

         // If we exit the loop, the process died — fail all pending requests
         pendingRequests.forEach((id, future) ->
                                         future.completeExceptionally(new TreeSitterUnavailableException("Node process died")));
         pendingRequests.clear();
     }

     // ─────────────────────────────────────────────────────────────────────────
     // RESULT & EXCEPTION TYPES
     // ─────────────────────────────────────────────────────────────────────────

     /**
      * Parsed result from the tree-sitter bridge.
      * Java callers map this into FlowNode / FlowEdge instances.
      */
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
             if (nodesArr != null && nodesArr.isArray()) {
                 for (JsonNode n : nodesArr) {
                     nodes.add(ParsedNode.fromJson(n));
                 }
             }
             JsonNode edgesArr = json.get("edges");
             if (edgesArr != null && edgesArr.isArray()) {
                 for (JsonNode e : edgesArr) {
                     edges.add(ParsedEdge.fromJson(e));
                 }
             }
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
             public final String  classContext;        // null if top-level function
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
                 List<String> decorators = new ArrayList<>();
                 JsonNode decoArr = n.get("decorators");
                 if (decoArr != null && decoArr.isArray()) {
                     for (JsonNode d : decoArr) decorators.add(d.asText());
                 }
                 return new ParsedNode(
                         n.path("id").asText(),
                         n.path("label").asText(),
                         n.path("filePath").asText(),
                         n.path("startLine").asInt(0),
                         n.path("endLine").asInt(0),
                         n.path("returnType").asText("unknown"),
                         n.path("cyclomaticComplexity").asInt(1),
                         n.path("classContext").isNull() ? null : n.path("classContext").asText(null),
                         n.path("isAsync").asBoolean(false),
                         decorators
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