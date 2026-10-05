package com.fonja.campaign.repository;

import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignLifecycleState;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    /**
     * The filter every public feed query shares: state, optional category, optional free-text
     * search on title and location. Null means "any" for both optional filters, which is why they
     * are written as {@code (:x is null or ...)} rather than as separate derived queries per
     * combination.
     */
    String PUBLIC_FEED_WHERE =
            " where c.lifecycleState = :state"
                    + " and (:category is null or c.category.slug = :category)"
                    + " and (:q is null"
                    + "      or lower(c.title) like lower(concat('%', :q, '%'))"
                    + "      or lower(c.location) like lower(concat('%', :q, '%')))";

    String PUBLIC_FEED_COUNT = "select count(c) from Campaign c" + PUBLIC_FEED_WHERE;

    @EntityGraph(attributePaths = {"creator", "category"})
    Page<Campaign> findByCreatorIdAndLifecycleStateNotOrderByCreatedAtDesc(
            Long creatorId, CampaignLifecycleState excludedState, Pageable pageable);

    @EntityGraph(attributePaths = {"creator", "category"})
    Page<Campaign> findByLifecycleStateOrderBySubmittedAtAsc(
            CampaignLifecycleState lifecycleState, Pageable pageable);

    /** Batch fed to the expiry sweep — capped so one pass can't pull an unbounded result set. */
    List<Campaign> findTop200ByLifecycleStateAndDeadlineLessThanEqual(
            CampaignLifecycleState lifecycleState, Instant cutoff);

    /**
     * The public feed, ordered by whatever the {@link Pageable} says. The creator is fetched in the
     * same query because every card shows their name; without the fetch join a page of twenty
     * campaigns is twenty-one queries.
     */
    @Query(
            value =
                    "select c from Campaign c join fetch c.creator join fetch c.category"
                            + PUBLIC_FEED_WHERE,
            countQuery = PUBLIC_FEED_COUNT)
    Page<Campaign> findPublicFeed(
            @Param("state") CampaignLifecycleState state,
            @Param("category") String category,
            @Param("q") String q,
            Pageable pageable);

    /**
     * The public feed, most raised first.
     *
     * <p>Separate from {@link #findPublicFeed} because the ordering key is not a column: it is the
     * sum of the campaign's settled donations, computed here as a correlated subquery so the count
     * query stays trivial and no {@code group by} has to enumerate the entity. Ties break on
     * approval date so the order is stable across pages. Callers must pass an unsorted pageable — a
     * sort on it would be appended after this one and silently ignored.
     */
    @Query(
            value =
                    "select c from Campaign c join fetch c.creator join fetch c.category"
                            + PUBLIC_FEED_WHERE
                            + " order by (select coalesce(sum(d.amount), 0)"
                            + "           from Donation d"
                            + "           where d.campaign = c"
                            + "           and d.status = com.fonja.donation.entity.DonationStatus.SUCCESS)"
                            + " desc, c.approvedAt desc, c.id desc",
            countQuery = PUBLIC_FEED_COUNT)
    Page<Campaign> findPublicFeedByMostRaised(
            @Param("state") CampaignLifecycleState state,
            @Param("category") String category,
            @Param("q") String q,
            Pageable pageable);
}
