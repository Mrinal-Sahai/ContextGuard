package io.contextguard.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(
        name = "reviews",
        indexes = {
                @Index(
                        name = "idx_review_platform_external",
                        columnList = "platform,owner,repository,external_id"
                )
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String platform; // 'github' or 'beanstalk'

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repository;

    @Column(name = "external_id", nullable = false)
    private String externalId; // PR number or review ID

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String author;

    @Column(nullable = false, length = 20)
    private String status; // 'open', 'closed', 'merged'

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;
}
