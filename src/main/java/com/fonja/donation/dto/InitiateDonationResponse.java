package com.fonja.donation.dto;

import com.fonja.donation.entity.Donation;
import java.math.BigDecimal;

/**
 * Everything the client needs to send a donor to checkout.
 *
 * <p>{@code checkoutUrl} is the only field that matters operationally — the rest is there so a
 * client can show the donor what they are about to pay, and so support has a reference to quote
 * without a database query.
 *
 * @param amount the donation itself, and the figure the ledger splits. Unaffected by the fee below
 * @param processingFee CyberPay's charge, which the donor pays <em>on top of</em> the donation.
 *     Null when CyberPay's initiate response omitted it — show the amount alone rather than
 *     inventing a number, since the donor will see the real figure on the checkout page either way
 * @param totalPayable what the donor's card is actually debited: {@code amount + processingFee}.
 *     Null whenever {@code processingFee} is, for the same reason
 */
public record InitiateDonationResponse(
        Long donationId,
        Long campaignId,
        BigDecimal amount,
        BigDecimal processingFee,
        BigDecimal totalPayable,
        String currency,
        String merchantReference,
        String checkoutUrl) {

    /**
     * Builds the response from the donation and CyberPay's reply.
     *
     * <p>The fee is surfaced here so a client can show "₦10,000 + ₦208 fee = ₦10,208"
     * <em>before</em> the redirect. A donor who first meets the charge on CyberPay's page reads it
     * as a surprise, and on a crowdfunding product that is a trust problem rather than a formatting
     * one.
     */
    public static InitiateDonationResponse from(
            Donation donation, String checkoutUrl, BigDecimal processingFee) {
        return new InitiateDonationResponse(
                donation.getId(),
                donation.getCampaign().getId(),
                donation.getAmount(),
                processingFee,
                processingFee == null ? null : donation.getAmount().add(processingFee),
                donation.getCurrency(),
                donation.getMerchantReference(),
                checkoutUrl);
    }
}
