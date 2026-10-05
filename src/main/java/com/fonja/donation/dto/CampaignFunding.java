package com.fonja.donation.dto;

import java.math.BigDecimal;

/**
 * How much a campaign has actually raised: the sum of its settled donations, gross of the platform
 * fee. Gross is the figure a donor and a creator both mean by "raised", and it is the figure
 * counted against the goal — the 7% split is a ledger concern, not a campaign one.
 *
 * <p>Built by a JPQL constructor expression, so the parameter order is load-bearing.
 */
public record CampaignFunding(Long campaignId, BigDecimal raisedAmount, long donationCount) {

    public static CampaignFunding none(Long campaignId) {
        return new CampaignFunding(campaignId, BigDecimal.ZERO, 0);
    }
}
