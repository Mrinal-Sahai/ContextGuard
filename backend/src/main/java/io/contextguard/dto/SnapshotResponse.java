package io.contextguard.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class SnapshotResponse {
    private Long id;
    private String summary;
    private String why;
    private List<String> risks;
    private List<String> reviewChecklist;
    private Integer contextScore;
    private String createdBy;
    private LocalDateTime createdAt;
}
