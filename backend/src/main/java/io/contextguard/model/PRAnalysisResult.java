package io.contextguard.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.contextguard.dto.PRIntelligenceResponse;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent cache of PR analysis results.
 *
 * Composite unique key: {owner, repo, prNumber}
 * This ensures idempotency.
 */
@Entity
@Table(
        name = "pr_analysis_results",
        uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "repo", "pr_number"})
)
@Data
public class PRAnalysisResult {

    @Transient
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Id
    private UUID id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repo;

    @Column(nullable = false, name = "pr_number")
    private Integer prNumber;

    @Column(columnDefinition = "TEXT")
    private String intelligenceJson; // Serialized PRIntelligenceResponse

    @Column(nullable = false)
    private Instant analyzedAt;

    @Column(columnDefinition = "TEXT")
    private String mermaidDiagram;

    @Column(columnDefinition = "TEXT")
    private String diagramVerificationNotes;

    @Column(columnDefinition = "JSONB")
    private String diagramMetrics;

    public void setIntelligence(PRIntelligenceResponse intelligence) {
        // Serialize to JSON using Jackson
        this.intelligenceJson = serializeToJson(intelligence);
    }

    public PRIntelligenceResponse toResponse() {
        // Deserialize from JSON
        return deserializeFromJson(this.intelligenceJson);
    }

    private String serializeToJson(PRIntelligenceResponse response) {
        try {
            // Use a copy so we don't modify any shared ObjectMapper configuration elsewhere
            ObjectMapper mapper = objectMapper.copy();
            // Support java.time.Instant properly
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Allow Jackson to use private fields (useful for classes without getters/setters)
            mapper.setVisibility(
                    VisibilityChecker.Std.defaultInstance()
                            .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                            .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
            );
            // Be lenient about empty beans
            mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize PRIntelligenceResponse", e);
        }
    }

    private PRIntelligenceResponse deserializeFromJson(String json) {
        try {
            ObjectMapper mapper = objectMapper.copy();
            mapper.registerModule(new JavaTimeModule());
            // Parse Instants as ISO strings
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Ignore unknown properties so schema changes don't break reading older objects
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            // Allow field access for POJOs that don't expose getters/setters
            mapper.setVisibility(
                    VisibilityChecker.Std.defaultInstance()
                            .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                            .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                            .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
            );

            return mapper.readValue(json, PRIntelligenceResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize PRIntelligenceResponse", e);
        }
    }
}
