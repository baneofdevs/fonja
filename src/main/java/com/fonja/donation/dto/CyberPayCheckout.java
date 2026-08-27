package com.fonja.donation.dto;

/**
 * What CyberPay hands back when a payment is initiated.
 *
 * @param transactionReference their reference for the payment — the one the webhook quotes and the
 *     one {@code GET /api/v1/payments/{reference}} takes
 * @param redirectUrl the hosted checkout page to send the donor to. Fonja never sees card details:
 *     they are entered on CyberPay's page, which is what keeps this service out of PCI scope
 *     entirely (technical spec §4.4)
 * @param charge CyberPay's fee on the transaction, in naira. Informational — it comes out of
 *     CyberPay's settlement, not out of the donation the ledger splits
 */
public record CyberPayCheckout(
        String transactionReference, String redirectUrl, java.math.BigDecimal charge) {}
