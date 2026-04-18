package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeConflictStatus {
    /** null = GitHub hasn't computed yet; true = clean; false = conflicts */
    private Boolean mergeable;
    /** clean | dirty | unstable | blocked | behind | draft | unknown */
    private String mergeableState;
    private boolean hasConflicts;
    private int conflictFileCount;
    /** Files changed in BOTH the PR and the base branch since fork (approximate intersection) */
    private List<String> conflictingFiles;
}
