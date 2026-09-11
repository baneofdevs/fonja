package com.fonja.payout.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * A creator's request to withdraw.
 *
 * <p>The amount is what the creator receives. The flat fee is added on top and taken from their
 * balance alongside it, so a request for ₦5,000 costs ₦5,200 in earnings — which is why {@code GET
 * /api/v1/payouts/quote} exists and reports the real maximum rather than leaving a client to work
 * it out and get it wrong.
 *
 * <p>No bank account field: withdrawals go to the account already linked to the creator. A payout
 * that could name its own destination would turn one stolen token into one stolen balance.
 *
 * @param amount at most two decimal places, because CyberPay is instructed in integer kobo
 */
public record RequestPayoutRequest(
        @NotNull(message = "amount is required")
                @DecimalMin(value = "100.00", message = "The minimum withdrawal is 100")
                @Digits(
                        integer = 12,
                        fraction = 2,
                        message = "amount must have at most 2 decimal places")
                BigDecimal amount) {}
