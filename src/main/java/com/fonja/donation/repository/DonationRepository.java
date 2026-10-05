package com.fonja.donation.repository;

import com.fonja.donation.dto.CampaignFunding;
import com.fonja.donation.dto.DonationTotals;
import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Raised totals for a set of campaigns in one query, so a page of the public feed costs one
     * aggregate rather than one per card. Campaigns with no settled donation are simply absent from
     * the result — the caller fills those in as zero.
     */
    @Query(
            "select new com.fonja.donation.dto.CampaignFunding("
                    + "d.campaign.id, sum(d.amount), count(d))"
                    + " from Donation d"
                    + " where d.status = :status and d.campaign.id in :campaignIds"
                    + " group by d.campaign.id")
    List<CampaignFunding> fundingByCampaignIds(
            @Param("campaignIds") Collection<Long> campaignIds,
            @Param("status") DonationStatus status);

    /** A donor's own history, every status — a pending checkout belongs on their list too. */
    @EntityGraph(attributePaths = "campaign")
    Page<Donation> findByDonorIdOrderByCreatedAtDesc(Long donorId, Pageable pageable);

    /** Settled donations to every campaign a creator owns, newest first. */
    @EntityGraph(attributePaths = {"campaign", "donor"})
    Page<Donation> findByCampaignCreatorIdAndStatusOrderByCreatedAtDesc(
            Long creatorId, DonationStatus status, Pageable pageable);

    /** A campaign's supporter list — settled only; nobody is thanked for an abandoned checkout. */
    @EntityGraph(attributePaths = {"campaign", "donor"})
    Page<Donation> findByCampaignIdAndStatusOrderByCreatedAtDesc(
            Long campaignId, DonationStatus status, Pageable pageable);

    @Query(
            "select new com.fonja.donation.dto.DonationTotals(coalesce(sum(d.amount), 0), count(d))"
                    + " from Donation d where d.donor.id = :userId and d.status = :status")
    DonationTotals totalsGivenBy(
            @Param("userId") Long userId, @Param("status") DonationStatus status);

    @Query(
            "select new com.fonja.donation.dto.DonationTotals(coalesce(sum(d.amount), 0), count(d))"
                    + " from Donation d"
                    + " where d.campaign.creator.id = :userId and d.status = :status")
    DonationTotals totalsReceivedBy(
            @Param("userId") Long userId, @Param("status") DonationStatus status);

    @Query(
            "select count(distinct d.donor.id) from Donation d"
                    + " where d.campaign.creator.id = :userId and d.status = :status")
    long countDistinctDonorsFor(
            @Param("userId") Long userId, @Param("status") DonationStatus status);

    /** Which of these users have a settled donation to the campaign — one query for a page. */
    @Query(
            "select distinct d.donor.id from Donation d"
                    + " where d.campaign.id = :campaignId and d.donor.id in :userIds"
                    + " and d.status = :status")
    List<Long> findDonorIdsAmong(
            @Param("campaignId") Long campaignId,
            @Param("userIds") Collection<Long> userIds,
            @Param("status") DonationStatus status);
}
