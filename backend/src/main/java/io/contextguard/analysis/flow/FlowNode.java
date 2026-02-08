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