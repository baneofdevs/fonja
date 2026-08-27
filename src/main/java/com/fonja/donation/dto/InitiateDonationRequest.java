package com.fonja.donation.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * A donor's request to give to a campaign.
 *
 * <p>Notably absent: the currency, which is taken from the campaign rather than the caller. A
 * donation is counted towards a campaign's goal, so letting the donor name a different currency
 * would silently change what the total means. And the donor id, which comes from the token — there
 * is no field here that could point a donation at someone else.
 *
 * @param campaignId the campaign to give to; must be ACTIVE
 * @param amount gross, in the campaign's currency, before Fonja's 7% split
 */
public record InitiateDonationRequest(
        @NotNull(message = "campaignId is required") Long campaignId,

        /*
         * Floor of ₦100. Below that the gateway's own fee is a large fraction of the gift, and the
         * 7% platform split rounds to nothing — the creator would be credited almost the whole
         * amount while Fonja carries the transaction cost. Two decimal places at most: naira and
         * kobo, nothing finer, because CyberPay is told the amount as an integer number of kobo.
         */
        @NotNull(message = "amount is required")
                @DecimalMin(value = "100.00", message = "The minimum donation is 100")
                @Digits(
                        integer = 12,
                        fraction = 2,
                        message = "amount must have at most 2 decimal places")
                BigDecimal amount) {}
