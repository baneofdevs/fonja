package com.fonja.campaign.repository;

import com.fonja.campaign.entity.Campaign;
import com.fonja.campaign.entity.CampaignLifecycleState;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    Page<Campaign> findByCreatorIdAndLifecycleStateNotOrderByCreatedAtDesc(
            Long creatorId, CampaignLifecycleState excludedState, Pageable pageable);

    Page<Campaign> findByLifecycleStateOrderBySubmittedAtAsc(
            CampaignLifecycleState lifecycleState, Pageable pageable);

    /** Batch fed to the expiry sweep — capped so one pass can't pull an unbounded result set. */
    List<Campaign> findTop200ByLifecycleStateAndDeadlineLessThanEqual(
            CampaignLifecycleState lifecycleState, Instant cutoff);

    // TODO: findByLifecycleState(ACTIVE, ...) for the public feed
}
