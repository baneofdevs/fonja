package com.fonja.donation.dto;

import com.fonja.donation.entity.Donation;
import java.math.BigDecimal;

/**
 * Everything the client needs to send a donor to checkout.
 *
 * <p>{@code checkoutUrl} is the only field that matters operationally — the rest is there so a
 * client can show the donor what they are about to pay, and so support has a reference to quote
 * without a database query.
 */
public record InitiateDonationResponse(
        Long donationId,
        Long campaignId,
        BigDecimal amount,
        String currency,
        String merchantReference,
        String checkoutUrl) {

    public static InitiateDonationResponse from(Donation donation, String checkoutUrl) {
        return new InitiateDonationResponse(
                donation.getId(),
                donation.getCampaign().getId(),
                donation.getAmount(),
                donation.getCurrency(),
                donation.getMerchantReference(),
                checkoutUrl);
    }
}
