package io.contextguard.analysis.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Enhanced flow edge with call metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowEdge {

    private String from;            // Source node ID
    private String to;              // Target node ID

    private EdgeType edgeType;      // CALL, INHERIT, IMPLEMENT, IMPORT
    private EdgeStatus status;      // ADDED, REMOVED, UNCHANGED

    private String sourceFile;      // File where call originates
    private int sourceLine;         // Line number of call

    private int callCount;          // Number of times called (if detectable)
    private CallContext context;    // CONSTRUCTOR, METHOD_BODY, STATIC_INIT

    public enum EdgeType {
        METHOD_CALL,        // Direct method invocation
        CONSTRUCTOR_CALL,   // new ClassName()
        INHERITANCE,        // extends
        IMPLEMENTATION,     // implements
        DEPENDENCY,         // Field injection, imports
        STATIC_CALL         // Static method call
    }

    public enum EdgeStatus {
        ADDED, REMOVED, UNCHANGED
    }

    public enum CallContext {
        CONSTRUCTOR, METHOD_BODY, STATIC_INITIALIZER, LAMBDA, ANONYMOUS_CLASS
    }
}
