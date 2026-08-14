package com.fonja.campaign.repository;

import com.fonja.campaign.entity.CampaignImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CampaignImageRepository extends JpaRepository<CampaignImage, Long> {

    List<CampaignImage> findByCampaignIdOrderBySortOrderAsc(Long campaignId);

    int countByCampaignId(Long campaignId);
}
