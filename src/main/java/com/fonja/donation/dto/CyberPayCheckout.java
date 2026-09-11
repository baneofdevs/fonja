package com.fonja.donation.dto;

/**
 * What CyberPay hands back when a payment is initiated.
 *
 * @param transactionReference their reference for the payment — the one the webhook quotes and the
 *     one {@code GET /api/v1/payments/{reference}} takes
 * @param redirectUrl the hosted checkout page to send the donor to. Fonja never sees card details:
 *     they are entered on CyberPay's page, which is what keeps this service out of PCI scope
 *     entirely (technical spec §4.4)
 * @param charge CyberPay's fee on the transaction, in naira — {@code 1.58% + ₦50}, derived from six
 *     staging transactions on 2026-08-28 and exact on every one. <strong>The donor pays it, added
 *     on top of the donation.</strong> Fonja is settled the full gross, so the ledger's 7% split is
 *     taken from the whole amount the donor chose and none of this comes out of Fonja's margin.
 *     Read it here rather than recomputing the formula: CyberPay can change their rate without
 *     telling us, and this figure is what the donor will actually be charged. May be null if they
 *     omit it
 */
public record CyberPayCheckout(
        String transactionReference, String redirectUrl, java.math.BigDecimal charge) {}
