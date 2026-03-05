package io.contextguard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
public class DiffMetrics {
    private int totalFilesChanged;
    private int linesAdded;
    private int linesDeleted;
    private int netLinesChanged;
    private Map<String, Integer> fileTypeDistribution;
    private int complexityDelta;
    private List<String> criticalFiles;
    private List<FileChangeSummary> fileChanges;
    private int maxCallDepth;
    private double avgChangedMethodCC;
    private List<String> hotspotMethodIds;
    private int removedPublicMethods;
    private int addedPublicMethods;
}
