package com.fonja.donation.entity;

import java.util.Locale;

public enum PaymentMethod {
    CARD,
    BANK_TRANSFER;

    /**
     * Maps CyberPay's {@code PaymentChannel} string onto our enum.
     *
     * <p>Returns null for anything unrecognised rather than throwing. The column is nullable, this
     * is display metadata, and refusing a confirmed payment because CyberPay added a channel we
     * have not seen would be the wrong trade entirely.
     */
    public static PaymentMethod fromChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        return switch (channel.trim().toLowerCase(Locale.ROOT).replace("_", "")) {
            case "banktransfer", "transfer", "bank" -> BANK_TRANSFER;
            case "card", "cards", "debitcard", "creditcard" -> CARD;
            default -> null;
        };
    }
}
