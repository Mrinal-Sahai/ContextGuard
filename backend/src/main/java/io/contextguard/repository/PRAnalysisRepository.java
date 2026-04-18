package io.contextguard.repository;

import io.contextguard.model.PRAnalysisResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PRAnalysisRepository extends JpaRepository<PRAnalysisResult, UUID> {

    Optional<PRAnalysisResult> findByOwnerAndRepoAndPrNumber(
            String owner, String repo, Integer prNumber);

    @Query("SELECT r FROM PRAnalysisResult r ORDER BY r.analyzedAt DESC")
    List<PRAnalysisResult> findTopNByOrderByAnalyzedAtDesc(Pageable pageable);
}
