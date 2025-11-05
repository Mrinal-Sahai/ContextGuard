package io.contextguard.service;

import io.contextguard.dto.SummaryData;
import io.contextguard.model.Review;
import io.contextguard.model.Snapshot;
import io.contextguard.repository.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SnapshotService {

    private final SnapshotRepository snapshotRepository;
    private final SummarizationService summarizationService;
    private final ScoringService scoringService;

    @Transactional
    public Snapshot processSnapshot(Snapshot snapshot) {
        // Generate summary
        SummaryData summary = summarizationService.generateSummary(snapshot);

        snapshot.setSummary(summary.getSummary());
        snapshot.setWhy(summary.getWhy());
        snapshot.setRisks(summary.getRisks());
        snapshot.setReviewChecklist(summary.getReviewChecklist());

        // Calculate context score
        int score = scoringService.calculateContextScore(snapshot.getReview(), snapshot);
        snapshot.setContextScore(score);

        return snapshotRepository.save(snapshot);
    }
}

