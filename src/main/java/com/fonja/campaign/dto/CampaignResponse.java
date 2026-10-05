package com.fonja.campaign.dto;

import com.fonja.campaign.entity.BeneficiaryType;
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

    /** The creator's display name, shown on every public card and detail page. */
    private String creatorName;

    /** The creator's profile photo, or null for initials. */
    private String creatorAvatarUrl;

    private String title;

    private String story;

    private CategoryRef category;

    /** The creator's own label when {@code category} is "others"; null otherwise. */
    private String categoryNote;

    private BeneficiaryType beneficiaryType;

    private String country;

    private String location;

    private BigDecimal goalAmount;

    private String currency;

    /**
     * Sum of settled donations, gross. Zero for a campaign that has taken nothing yet. Compared
     * against {@link #goalAmount} by clients to render progress; the backend does not store a
     * percentage because it would go stale.
     */
    private BigDecimal raisedAmount;

    /** Number of settled donations — not distinct donors. */
    private long donationCount;

    private String coverMediaPath;

    /** Optional additional images, in display order. Each carries its id so it can be removed. */
    private List<CampaignImageResponse> images;

    public record CampaignImageResponse(Long id, String path) {}

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
