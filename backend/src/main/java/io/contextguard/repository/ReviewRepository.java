package io.contextguard.repository;

import io.contextguard.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByPlatformAndOwnerAndRepositoryAndExternalId(
            String platform, String owner, String repository, String externalId
    );

    @Query("SELECT r FROM Review r WHERE r.status = 'open' AND r.lastActivityAt < :threshold")
    List<Review> findStaleReviews(LocalDateTime threshold);
}
