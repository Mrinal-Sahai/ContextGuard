package io.contextguard.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ReviewResponse {
    private Long id;
    private String platform;
    private String owner;
    private String repository;
    private String externalId;
    private String title;
    private String author;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime lastActivityAt;
    private Map<String, Object> metadata;
    private SnapshotResponse latestSnapshot;
}
