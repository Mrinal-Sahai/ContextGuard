package io.contextguard.dto;

import io.contextguard.client.AIProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PRAnalysisRequest {

    @NotBlank(message = "PR URL is required")
    @Pattern(
            regexp = "https://github\\.com/[^/]+/[^/]+/pull/\\d+",
            message = "Invalid GitHub PR URL format"
    )
    private String prUrl;
    private AIProvider aiProvider;
}
