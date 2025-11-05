package io.contextguard.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Snapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "commit_list", columnDefinition = "jsonb")
    private List<Map<String, String>> commitList;

    @Column(name = "pr_body", columnDefinition = "TEXT")
    private String prBody;

    @Column(name = "diff_url")
    private String diffUrl; // MinIO S3 key

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "ticket_ids", columnDefinition = "text[]")
    private List<String> ticketIds;

    @Column(name = "created_by", nullable = false)
    private String createdBy; // 'webhook', 'manual', 'scheduled'

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String why;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> risks;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "review_checklist", columnDefinition = "text[]")
    private List<String> reviewChecklist;

    @Column(name = "context_score")
    private Integer contextScore;
}
