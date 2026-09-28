package com.vyoog.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewCommentRepository extends JpaRepository<ReviewComment, UUID> {
    List<ReviewComment> findAllByReviewIdOrderByCreatedAtAsc(UUID reviewId);
}
