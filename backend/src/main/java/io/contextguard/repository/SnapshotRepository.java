
package io.contextguard.repository;

import io.contextguard.model.Snapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {
    @Query("SELECT s FROM Snapshot s WHERE s.review.id = :reviewId ORDER BY s.createdAt DESC LIMIT 1")
    Optional<Snapshot> findLatestByReviewId(Long reviewId);
}
