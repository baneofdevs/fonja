package com.fonja.campaign.dto;

import com.fonja.campaign.entity.BeneficiaryType;
import com.fonja.campaign.entity.CampaignCategory;
import com.fonja.campaign.entity.CampaignLifecycleState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * Outbound view of a campaign.
 *
 * <p>No bean-validation annotations here on purpose: constraints belong on the inbound request
 * records, which is where they are actually enforced. On a response they would be inert.
 */
@Getter
@Builder
public class CampaignResponse {

    private Long id;

    private Long creatorId;

    private String title;

    private String story;

    private CampaignCategory category;

    private BeneficiaryType beneficiaryType;

    private String country;

    private String location;

    private BigDecimal goalAmount;

    private String currency;

    private String coverMediaPath;

    /** Optional additional images, in display order. */
    private List<String> images;

    private CampaignLifecycleState lifecycleState;

    /**
     * Why the last review rejected this campaign. Null unless it was rejected and not resubmitted.
     */
    private String rejectionReason;

    private Instant deadline;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant submittedAt;

    private Instant approvedAt;
}
