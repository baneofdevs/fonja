package com.fonja.payout.repository;

import com.fonja.payout.entity.PayoutRequest;
import com.fonja.payout.entity.PayoutStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, Long> {

    /** A creator's own withdrawal history, newest first. */
    Page<PayoutRequest> findByCreatorIdOrderByRequestedAtDesc(Long creatorId, Pageable pageable);

    /**
     * The admin queue: everything that has not reached a terminal state, oldest first so the
     * longest-waiting creator is dealt with before the newest one.
     */
    Page<PayoutRequest> findByStatusInOrderByRequestedAtAsc(
            List<PayoutStatus> statuses, Pageable pageable);

    /**
     * The dispatch job's batch: payouts that have never been sent anywhere.
     *
     * <p>The {@code dispatchAttemptedAt is null} half is the important one. A payout whose
     * instruction timed out stays REQUESTED but is excluded here forever, because nothing on this
     * side can tell whether CyberPay accepted it and re-sending would pay the creator twice.
     *
     * <p>Bounded and oldest-first: a backlog drains in the order it formed, and one run can never
     * take on more work than it can finish.
     */
    List<PayoutRequest> findTop50ByStatusAndDispatchAttemptedAtIsNullOrderByRequestedAtAsc(
            PayoutStatus status);

    /** Payouts a human needs to look at: sent once, with no answer, and no safe automatic retry. */
    long countByStatusAndDispatchAttemptedAtIsNotNull(PayoutStatus status);

    long countByStatus(PayoutStatus status);
}
