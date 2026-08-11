package com.fonja.campaign.repository;

import com.fonja.campaign.entity.Campaign;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {
    // TODO: findByLifecycleState for the feed (ACTIVE only)
}
