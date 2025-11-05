
package io.contextguard.service.summarizer;

import io.contextguard.dto.SummaryData;
import io.contextguard.model.Snapshot;

public interface LLMClient {
    SummaryData generateSummary(Snapshot snapshot, String sanitizedContent);
}
