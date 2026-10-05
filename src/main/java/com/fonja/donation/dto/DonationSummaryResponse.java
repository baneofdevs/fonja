package com.fonja.donation.dto;

import java.math.BigDecimal;

/**
 * The numbers on a signed-in user's dashboard, in both directions.
 *
 * @param totalGiven what this user has donated, settled only
 * @param donationsMade how many settled donations they have made
 * @param totalReceived gross settled donations across every campaign they created
 * @param donationsReceived how many settled donations their campaigns have taken
 * @param distinctDonors how many different people have given to their campaigns
 */
public record DonationSummaryResponse(
        BigDecimal totalGiven,
        long donationsMade,
        BigDecimal totalReceived,
        long donationsReceived,
        long distinctDonors) {}
