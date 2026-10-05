package com.fonja.donation.dto;

import com.fonja.donation.entity.Donation;
import com.fonja.donation.entity.DonationStatus;
import com.fonja.donation.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One donation as a person sees it — a donor looking at what they gave, a creator looking at what
 * came in, or a visitor reading a campaign's supporter list.
 *
 * @param donorName the donor's display name. Present on the creator's and the public campaign
 *     lists; null on the donor's own list, where it would only be their own name
 */
public record DonationResponse(
        Long id,
        Long campaignId,
        String campaignTitle,
        String donorName,
        BigDecimal amount,
        String currency,
        PaymentMethod paymentMethod,
        DonationStatus status,
        Instant createdAt) {

    public static DonationResponse forDonor(Donation donation) {
        return from(donation, null);
    }

    public static DonationResponse withDonor(Donation donation) {
        return from(donation, donation.getDonor().getFullName());
    }

    private static DonationResponse from(Donation donation, String donorName) {
        return new DonationResponse(
                donation.getId(),
                donation.getCampaign().getId(),
                donation.getCampaign().getTitle(),
                donorName,
                donation.getAmount(),
                donation.getCurrency(),
                donation.getPaymentMethod(),
                donation.getStatus(),
                donation.getCreatedAt());
    }
}
