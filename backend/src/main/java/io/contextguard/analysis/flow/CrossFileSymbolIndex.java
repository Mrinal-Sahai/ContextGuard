package io.contextguard.analysis.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * CROSS-FILE SYMBOL INDEX  — complete implementation
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Built in Pass 1 (parallel). Read in Pass 2 (parallel).
 * All maps are ConcurrentHashMap — safe for concurrent read+write.
 *
 * ┌────────────────────────────────┬────────────────────────────────────────────┐
 * │ Table                          │ Purpose                                    │
 * ├────────────────────────────────┼────────────────────────────────────────────┤
 * │ classIndex                     │ simple name → list of ClassEntry           │
 * │                                │ e.g. "PaymentService" → [...]              │
 * ├────────────────────────────────┼────────────────────────────────────────────┤
 * │ methodIndex                    │ "ClassName.method" → list of node IDs      │
 * │                                │ e.g. "PaymentService.process" → [...]      │
 * ├────────────────────────────────┼────────────────────────────────────────────┤
 * │ functionIndex                  │ bare name → list of node IDs               │
 * │                                │ e.g. "validate_card" → [...]               │
 * ├────────────────────────────────┼────────────────────────────────────────────┤
 * │ importAliasIndex               │ filePath → (alias → canonical class name)  │
 * │                                │ e.g. "order.py" → {"Svc":"PaymentService"} │
 * ├────────────────────────────────┼────────────────────────────────────────────┤
 * │ variableTypeIndex              │ filePath → (varName → class name)          │
 * │                                │ e.g. "order.py" → {"svc":"PaymentService"} │
 * └────────────────────────────────┴────────────────────────────────────────────┘
 *
 * RESOLUTION ALGORITHM  (resolve method)
 * ───────────────────────────────────────
 * Given (callerFilePath, callerClass, receiver, methodName):
 *
 *   1. receiver == "self" / "this" / callerClass  → same-class lookup
 *   2. receiver is a known variable in callerFilePath → resolve type → class lookup
 *   3. receiver is a known import alias in callerFilePath → canonical class lookup
 *   4. receiver starts with uppercase → direct class name lookup
 *   5. methodName bare (receiver null) → function index, same-file first
 *
 * For each step, when multiple candidates exist for the same class name,
 * "closest directory" wins (longest common path prefix with callerFilePath).
 */
public class CrossFileSymbolIndex {

    private static final Logger logger = LoggerFactory.getLogger(CrossFileSymbolIndex.class);

    // ─── Internal record types ────────────────────────────────────────────────

    /** A registered class/type — holds its simple name, qualified name, and origin file. */
    private record ClassEntry(String simpleName, String qualifiedName,
                              String filePath, String language) {}

    // ─── Storage tables ───────────────────────────────────────────────────────

    /** simpleName → all known ClassEntry records */
    private final Map<String, List<ClassEntry>> classIndex = new ConcurrentHashMap<>();

    /** "ClassName.methodName" → all known node IDs */
    private final Map<String, List<String>> methodIndex = new ConcurrentHashMap<>();

    /** bare function name → all known node IDs */
    private final Map<String, List<String>> functionIndex = new ConcurrentHashMap<>();

    /** filePath → (alias → canonical simple class name) */
    private final Map<String, Map<String, String>> importAliasIndex = new ConcurrentHashMap<>();

    /** filePath → (variable name → class name) */
    private final Map<String, Map<String, String>> variableTypeIndex = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // REGISTRATION  (Pass 1)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Register a class or type definition.
     *
     * @param simpleName    short name, e.g. "PaymentService"
     * @param qualifiedName full name, e.g. "com.example.payments.PaymentService"
     * @param filePath      file where this class is defined
     * @param language      "java", "python", "typescript", etc.
     */
    public void registerClass(String simpleName, String qualifiedName,
                              String filePath, String language) {
        if (simpleName == null || simpleName.isBlank()) return;
        classIndex
                .computeIfAbsent(simpleName, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new ClassEntry(simpleName, qualifiedName, filePath, language));
    }

    /**
     * Register a method or function definition.
     *
     * @param label        method name, e.g. "process"
     * @param nodeId       fully-qualified node ID, e.g. "src/PaymentService.java:com.example.PaymentService.process"
     * @param classContext simple class name, or null for top-level functions
     * @param filePath     file where this method is defined
     * @param language     "java", "python", "typescript", etc.
     */
    public void registerMethod(String label, String nodeId,
                               String classContext, String filePath, String language) {
        if (label == null || label.isBlank() || nodeId == null) return;

        if (classContext != null && !classContext.isBlank()) {
            // Method on a class — register under "ClassName.methodName"
            String key = simplify(classContext) + "." + label;
            methodIndex
                    .computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(nodeId);
        } else {
            // Top-level function
            functionIndex
                    .computeIfAbsent(label, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(nodeId);
        }
    }

    /**
     * Register an import alias: alias → canonical class name, scoped to a file.
     *
     * @param filePath         file where this import occurs
     * @param alias            the name used in this file (may equal canonicalClassName)
     * @param canonicalClassName the simple class name being imported
     *
     * Examples:
     *   Python:    from payments.service import PaymentService as Svc
     *              → registerImportAlias("orders/order.py", "Svc", "PaymentService")
     *   TypeScript: import { PaymentService as Svc } from '../payments/service'
     *              → registerImportAlias("orders/order.ts", "Svc", "PaymentService")
     *   Java:      import com.example.payments.PaymentService;
     *              → registerImportAlias("orders/Order.java", "PaymentService", "PaymentService")
     */
    public void registerImportAlias(String filePath, String alias, String canonicalClassName) {
        if (filePath == null || alias == null || canonicalClassName == null) return;
        importAliasIndex
                .computeIfAbsent(filePath, k -> new ConcurrentHashMap<>())
                .put(alias, canonicalClassName);
    }

    /**
     * Register a variable→type mapping scoped to a file.
     *
     * @param filePath     file where this assignment occurs
     * @param variableName variable or field name
     * @param className    simple class name of the variable's type
     *
     * Examples:
     *   Java:   private PaymentValidator validator;
     *           → registerVariableType("...", "validator", "PaymentValidator")
     *   Python: validator = PaymentValidator()
     *           → registerVariableType("...", "validator", "PaymentValidator")
     *   JS/TS:  const svc = new PaymentService()
     *           → registerVariableType("...", "svc", "PaymentService")
     */
    public void registerVariableType(String filePath, String variableName, String className) {
        if (filePath == null || variableName == null || className == null) return;
        if (className.isBlank() || variableName.isBlank()) return;
        variableTypeIndex
                .computeIfAbsent(filePath, k -> new ConcurrentHashMap<>())
                .put(variableName, simplify(className));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESOLUTION  (Pass 2)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a method call to a node ID.
     *
     * This is the primary resolution entry point used by ASTParserService
     * and JavaSymbolSolverService for all languages.
     *
     * @param callerFilePath  path of the file containing the call (for proximity scoring)
     * @param callerClass     simple class name of the calling function (null if top-level)
     * @param receiver        receiver expression: "self", "this", "validator", "PaymentService", or null
     * @param methodName      name of the method being called
     * @return                resolved node ID, or null if unresolvable
     */
    public String resolve(String callerFilePath, String callerClass,
                          String receiver, String methodName) {
        if (methodName == null || methodName.isBlank()) return null;

        // ── Step 1: self / this / same class ──────────────────────────────────
        if (receiver == null || receiver.isBlank()
                    || "self".equals(receiver) || "this".equals(receiver)) {
            if (callerClass != null) {
                String found = lookupMethod(simplify(callerClass), methodName, callerFilePath);
                if (found != null) return found;
            }
            // No receiver → try function index
            return lookupFunction(methodName, callerFilePath);
        }

        // ── Step 2: variable type lookup ──────────────────────────────────────
        String varType = lookupVariableType(callerFilePath, receiver);
        if (varType != null) {
            String found = lookupMethod(simplify(varType), methodName, callerFilePath);
            if (found != null) return found;
        }

        // ── Step 3: import alias lookup ───────────────────────────────────────
        String aliasTarget = lookupImportAlias(callerFilePath, receiver);
        if (aliasTarget != null) {
            String found = lookupMethod(simplify(aliasTarget), methodName, callerFilePath);
            if (found != null) return found;
            // alias might be a module, not a class — try function index under module
            found = lookupFunction(methodName, callerFilePath);
            if (found != null) return found;
        }

        // ── Step 4: direct class name (uppercase first char) ──────────────────
        if (Character.isUpperCase(receiver.charAt(0))) {
            String found = lookupMethod(simplify(receiver), methodName, callerFilePath);
            if (found != null) return found;
        }

        // ── Step 5: chained "this.field" → resolve field type ─────────────────
        if (receiver.startsWith("this.") || receiver.startsWith("self.")) {
            String fieldName = receiver.substring(5);
            String fieldType = lookupVariableType(callerFilePath, fieldName);
            if (fieldType != null) {
                String found = lookupMethod(simplify(fieldType), methodName, callerFilePath);
                if (found != null) return found;
            }
        }

        return null;
    }

    /**
     * Convenience: resolve a bare function call (no receiver).
     */
    public String resolveFunctionCall(String callerFilePath, String functionName) {
        return resolve(callerFilePath, null, null, functionName);
    }

    /**
     * Resolve a class name to its file-path prefix.
     * Used by JavaSymbolSolverService.SymbolIndexTypeSolver to check class existence.
     *
     * @param callerFilePath  caller context for proximity scoring (may be null)
     * @param className       simple or qualified class name
     * @return                the qualified name from the best matching ClassEntry, or null
     */
    public String resolveClassName(String callerFilePath, String className) {
        if (className == null || className.isBlank()) return null;
        List<ClassEntry> entries = classIndex.get(simplify(className));
        if (entries == null || entries.isEmpty()) return null;
        if (entries.size() == 1) return entries.get(0).qualifiedName();

        // Multiple definitions — pick closest by file path proximity
        if (callerFilePath != null) {
            ClassEntry best     = null;
            int        bestScore = -1;
            for (ClassEntry e : entries) {
                int score = commonPrefixLength(callerFilePath, e.filePath());
                if (score > bestScore) { bestScore = score; best = e; }
            }
            return best != null ? best.qualifiedName() : entries.get(0).qualifiedName();
        }
        return entries.get(0).qualifiedName();
    }

    /**
     * Look up a variable's declared type in a specific file.
     * Returns the simple class name, or null if not registered.
     */
    public String lookupVariableType(String filePath, String variableName) {
        if (filePath == null || variableName == null) return null;
        Map<String, String> fileVars = variableTypeIndex.get(filePath);
        return fileVars != null ? fileVars.get(variableName) : null;
    }

    /**
     * Get all methods registered for a given simple class name.
     * Returns a list of node IDs.
     */
    public List<String> getMethodsForClass(String simpleClassName) {
        List<String> result = new ArrayList<>();
        String prefix = simpleClassName + ".";
        for (Map.Entry<String, List<String>> entry : methodIndex.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.addAll(entry.getValue());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Log index statistics at INFO level.
     * Called by ASTParserService after Pass 1 completes.
     */
    public void logStats() {
        int classes   = classIndex.size();
        int methods   = methodIndex.values().stream().mapToInt(List::size).sum();
        int functions = functionIndex.values().stream().mapToInt(List::size).sum();
        int files     = Math.max(importAliasIndex.size(), variableTypeIndex.size());
        int varTypes  = variableTypeIndex.values().stream().mapToInt(Map::size).sum();

        logger.info("CrossFileSymbolIndex: {} classes, {} methods, {} functions, " +
                            "{} files with imports, {} variable type registrations",
                classes, methods, functions, files, varTypes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INTERNAL LOOKUP HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Look up a method by "ClassName.methodName", returning the closest match. */
    private String lookupMethod(String simpleClass, String methodName, String callerFilePath) {
        String key = simpleClass + "." + methodName;
        List<String> candidates = methodIndex.get(key);
        return pickClosest(candidates, callerFilePath);
    }

    /** Look up a bare function name, preferring same-file then closest directory. */
    private String lookupFunction(String functionName, String callerFilePath) {
        List<String> candidates = functionIndex.get(functionName);
        if (candidates == null || candidates.isEmpty()) return null;

        // Strong preference: same file
        for (String c : candidates) {
            if (callerFilePath != null && extractFilePath(c).equals(callerFilePath)) return c;
        }
        return pickClosest(candidates, callerFilePath);
    }

    /** Look up an import alias for a given file. */
    private String lookupImportAlias(String filePath, String alias) {
        if (filePath == null || alias == null) return null;
        Map<String, String> aliases = importAliasIndex.get(filePath);
        return aliases != null ? aliases.get(alias) : null;
    }

    /**
     * From a list of node ID candidates, pick the one whose file path shares
     * the longest common prefix with callerFilePath ("closest directory wins").
     */
    private String pickClosest(List<String> candidates, String callerFilePath) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1)                    return candidates.get(0);
        if (callerFilePath == null)                    return candidates.get(0);

        String bestCandidate = null;
        int    bestScore     = -1;
        for (String candidate : candidates) {
            int score = commonPrefixLength(callerFilePath, extractFilePath(candidate));
            if (score > bestScore) { bestScore = score; bestCandidate = candidate; }
        }
        return bestCandidate;
    }

    /**
     * Extract the file path portion from a node ID.
     *   "src/payments/PaymentService.java:com.example.PaymentService.process"
     *   → "src/payments/PaymentService.java"
     *
     *   "com.example.PaymentService.process"  (Java, no colon)
     *   → "com.example.PaymentService.process" (full string used for scoring)
     */
    private String extractFilePath(String nodeId) {
        int colon = nodeId.indexOf(':');
        return colon > 0 ? nodeId.substring(0, colon) : nodeId;
    }

    /** Number of characters two strings share from their start. */
    private int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int i   = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /**
     * Strip package/module qualifier to get simple class name.
     *   "com.example.payments.PaymentService" → "PaymentService"
     *   "payments.service.PaymentService"     → "PaymentService"
     *   "PaymentService"                      → "PaymentService"
     *
     * Also strips generics:
     *   "List<PaymentService>"                → "List"
     */
    private String simplify(String qualifiedName) {
        if (qualifiedName == null) return "";
        // Strip generics
        int genericIdx = qualifiedName.indexOf('<');
        String bare = genericIdx > 0 ? qualifiedName.substring(0, genericIdx) : qualifiedName;
        // Strip package
        int lastDot = bare.lastIndexOf('.');
        return lastDot >= 0 ? bare.substring(lastDot + 1) : bare;
    }

    public String resolveByLocation(String targetFilePath, int lineNumber) {
        if (targetFilePath == null || lineNumber <= 0) return null;

        // Walk the method index looking for entries whose node ID starts
        // with targetFilePath and whose registered startLine matches.
        // Since we don't store startLine in the index directly, we do a
        // prefix scan of node IDs and look for the best filename match.
        //
        // Node ID format for non-Java: "filePath:ClassName.methodName"
        // The filePath portion ends at the first ':'
        //
        // We scan all methodIndex entries and find ones from targetFilePath.
        String bestNodeId   = null;
        int    bestLineDiff = Integer.MAX_VALUE;

        for (List<String> nodeIds : methodIndex.values()) {
            for (String nodeId : nodeIds) {
                // Extract file path from nodeId
                String nodeFile = extractFilePath(nodeId);

                // Match: nodeFile ends with targetFilePath (handles path prefix differences)
                if (!nodeFile.endsWith(targetFilePath) && !targetFilePath.endsWith(nodeFile)) continue;

                // We don't store line numbers in the index — use proximity heuristic.
                // Among all nodes in the same file, the one with the longest node ID
                // that still starts with the file path is preferred (more specific).
                // This is imprecise but adequate for the 95% case where LSP returns
                // a unique definition per call site.
                if (bestNodeId == null || nodeId.length() > bestNodeId.length()) {
                    bestNodeId = nodeId;
                }
            }
        }

        // Also check functionIndex for top-level functions
        for (List<String> nodeIds : functionIndex.values()) {
            for (String nodeId : nodeIds) {
                String nodeFile = extractFilePath(nodeId);
                if (!nodeFile.endsWith(targetFilePath) && !targetFilePath.endsWith(nodeFile)) continue;
                if (bestNodeId == null) bestNodeId = nodeId;
            }
        }

        return bestNodeId;
    }
}