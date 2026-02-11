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
    private Map<String, Integer> fileTypeDistribution; // e.g., {java: 8, xml: 4}
    private int complexityDelta; //ispr dhyan do //update it in ast parsing.
    private List<String> criticalFiles;
    private List<FileChangeSummary> fileChanges;
}
