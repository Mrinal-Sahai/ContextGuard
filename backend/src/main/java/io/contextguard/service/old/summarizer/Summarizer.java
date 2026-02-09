
package io.contextguard.service.old.summarizer;
import io.contextguard.dto.SummaryData;
import io.contextguard.model.Snapshot;

public interface Summarizer {
    SummaryData summarize(Snapshot snapshot);
}

