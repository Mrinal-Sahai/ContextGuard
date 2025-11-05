package io.contextguard.service.summarizer;

import io.contextguard.dto.SummaryData;
import io.contextguard.model.Snapshot;
import org.springframework.stereotype.Service;
import java.util.Arrays;

@Service("stubLLMClient")
public class LocalStubLLMClient implements LLMClient {

    @Override
    public SummaryData generateSummary(Snapshot snapshot, String sanitizedContent) {
        // Return canned response for demo purposes
        SummaryData summary = new SummaryData();

        summary.setSummary("This PR introduces new functionality to improve system performance.");

        summary.setWhy("Addresses user feedback regarding slow response times in the dashboard.");

        summary.setReviewChecklist(Arrays.asList(
                "Verify performance benchmarks meet requirements",
                "Check for any regression in existing features",
                "Validate error handling for edge cases"
        ));

        summary.setRisks(Arrays.asList(
                "Potential impact on memory usage",
                "May require additional testing under load"
        ));

        return summary;
    }
}
