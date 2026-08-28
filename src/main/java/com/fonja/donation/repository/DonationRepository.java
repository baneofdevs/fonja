package com.fonja.donation.repository;

import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DonationRepository extends JpaRepository<Donation, Long> {

    /**
     * Resolves a CyberPay notification back to the donation it belongs to. Backed by the unique
     * constraint {@code uk_donations_gateway_reference}, which is what makes a webhook retry find
     * the same row instead of creating a second one.
     */
    Optional<Donation> findByGatewayReference(String gatewayReference);

    /**
     * The fallback lookup for a webhook. If the initiate call reached CyberPay but our response
     * handling died before storing their reference, this is the only handle left on the donation —
     * and CyberPay echoes our {@code MerchantRef} back on every notification.
     */
    Optional<Donation> findByMerchantReference(String merchantReference);

    /**
     * A page of donations for the sweep to re-query, oldest first.
     *
     * <p>Bounded at both ends. The recent end skips checkouts a donor may still be sitting on; the
     * old end stops the sweep re-querying long-dead rows forever, which would grow into a permanent
     * hourly cost that never resolves anything. Backed by {@code idx_donations_status_created_at}.
     */
    List<Donation> findTop100ByStatusAndCreatedAtBetweenOrderByCreatedAtAsc(
            DonationStatus status, Instant from, Instant to);

    /** How many donations the sweep has given up on, for the one warning line it logs per run. */
    long countByStatusAndCreatedAtBefore(DonationStatus status, Instant before);
}
