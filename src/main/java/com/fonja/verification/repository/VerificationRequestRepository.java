package com.fonja.verification.repository;

import com.fonja.verification.entity.VerificationRequest;
import com.fonja.verification.entity.VerificationStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, Long> {

    Optional<VerificationRequest> findByUserIdAndStatus(Long userId, VerificationStatus status);

    /** The admin review queue — oldest first, so nothing sits unreviewed indefinitely. */
    Page<VerificationRequest> findByStatusOrderBySubmittedAtAsc(
            VerificationStatus status, Pageable pageable);

    Optional<VerificationRequest> findFirstByUserIdOrderBySubmittedAtDesc(Long userId);
}
