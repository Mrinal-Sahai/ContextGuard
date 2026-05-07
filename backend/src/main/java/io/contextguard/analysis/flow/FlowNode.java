package io.contextguard.analysis.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Enhanced flow node with AST-derived metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowNode {

    private String id;              // Fully qualified name (e.g., "com.example.Service.processData")
    private String label;           // Display name
    private NodeType type;          // METHOD, CLASS, MODULE, FUNCTION
    private NodeStatus status;      // ADDED, REMOVED, MODIFIED, UNCHANGED

    private String filePath;        // Source file path
    private int startLine;          // Line number in file
    private int endLine;

    private Set<String> annotations; // @Override, @Transactional, etc.
    private String returnType;       // For methods

    /**
     * True when the method is explicitly public (or effectively public for interface methods).
     *
     * Java:       set from JavaParser's method.isPublic() — accurate.
     * TS/Py/Go:   set heuristically — private only when name starts with '_' or '#'.
     *             Best-effort; improves on the old "non-void return type" proxy.
     *
     * Default is true so that PRs parsed before this field existed still count
     * non-void methods the old way (conservative: over-counts rather than under-counts).
     */
    @Builder.Default
    private boolean isPublic = true;

    private int cyclomaticComplexity; // Computed from AST

    private int inDegree;           // Number of incoming calls
    private int outDegree;          // Number of outgoing calls
    private double centrality;      // Graph centrality score

    public enum NodeType {
        METHOD, FUNCTION, CLASS, MODULE, INTERFACE, ENUM
    }
    public enum NodeStatus {
        ADDED, REMOVED, MODIFIED, UNCHANGED
    }
}