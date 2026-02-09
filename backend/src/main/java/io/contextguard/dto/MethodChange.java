package io.contextguard.dto;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Represents a single method-level change.
 *
 * Contains actual AST-derived complexity, not estimates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MethodChange {

    private String methodName;
    private String methodSignature;       // e.g., "processData(String, int)"
    private MethodChangeType changeType;  // ADDED, MODIFIED, DELETED, UNCHANGED

    // Complexity (from AST)
    private int complexityBefore;         // 0 if added
    private int complexityAfter;          // 0 if deleted
    private int complexityDelta;          // after - before

    // Line info
    private int startLine;
    private int endLine;
    private int linesChanged;

    // Metadata
    private String returnType;
    private Set<String> annotations;

    // Change details
    private String changeDescription;     // e.g., "Complexity increased from 5 to 12"

    public enum MethodChangeType {
        ADDED,      // New method in head
        DELETED,    // Method removed from base
        MODIFIED,   // Method exists in both but changed
        UNCHANGED   // Method exists in both, no changes
    }
}
