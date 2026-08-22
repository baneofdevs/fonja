package com.fonja.campaign.repository;

import com.fonja.campaign.entity.CampaignImage;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignImageRepository extends JpaRepository<CampaignImage, Long> {

    List<CampaignImage> findByCampaignIdOrderBySortOrderAsc(Long campaignId);

    /**
     * Batch variant for mapping a page of campaigns: one query for the whole page instead of one
     * per campaign.
     */
    List<CampaignImage> findByCampaignIdInOrderByCampaignIdAscSortOrderAsc(
            Collection<Long> campaignIds);

    /**
     * Highest sort order currently in use, or -1 when the campaign has no images. Counting rows
     * instead would repeat a position after any delete.
     */
    @Query(
            "select coalesce(max(i.sortOrder), -1) from CampaignImage i"
                    + " where i.campaign.id = :campaignId")
    int findMaxSortOrder(@Param("campaignId") Long campaignId);
}
