package com.fonja.donation.dto;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * What CyberPay says about a transaction when we ask them directly — the result of {@code GET
 * /api/v1/payments/{reference}}.
 *
 * <p>This, not the webhook body, is what the ledger is allowed to act on. A valid webhook signature
 * proves a message came from CyberPay; only a re-query proves the money settled.
 *
 * @param reference CyberPay's own transaction reference
 * @param status their status string, kept verbatim so an unrecognised value is visible in logs
 *     rather than flattened into an enum constant that loses it
 * @param amount the gross amount <strong>in naira</strong>, already converted from the kobo integer
 *     CyberPay returns
 * @param paymentChannel their channel string, e.g. {@code Card}; may be null. The webhook carries
 *     the same thing, so this matters only on the sweep path, where there is no notification to
 *     read it from and this is the sole source for the donation's payment method
 */
public record CyberPayTransaction(
        String reference, String status, BigDecimal amount, String paymentChannel) {

    private static final Set<String> SUCCESSFUL = Set.of("successful", "success", "completed");

    /**
     * Statuses that will never become successful. Anything outside both sets — including a status
     * CyberPay adds after this was written — counts as still pending, which makes the handler ask
     * again later. That is the safe default in both directions: an unknown status never credits a
     * creator, and never permanently fails a donation that might still complete.
     */
    private static final Set<String> FAILED =
            Set.of(
                    "failed",
                    "abandoned",
                    "cancelled",
                    "canceled",
                    "reversed",
                    "expired",
                    "declined");

    public boolean isSuccessful() {
        return SUCCESSFUL.contains(normalised());
    }

    public boolean isFailed() {
        return FAILED.contains(normalised());
    }

    public boolean isPending() {
        return !isSuccessful() && !isFailed();
    }

    private String normalised() {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }
}
