package com.fonja.payout.entity;

/**
 * Where a withdrawal has got to. Stored as a string, so adding a constant needs no migration.
 *
 * <p>Only {@link #FAILED} returns money to the creator's balance. The reserve is written the moment
 * a payout is {@link #REQUESTED} — see {@code LedgerService.recordWithdrawal} — so every other state
 * simply leaves it in place.
 */
public enum PayoutStatus {

    /** Reserved against the creator's earnings; nothing has been sent to the gateway yet. */
    REQUESTED,

    /**
     * An instruction is with CyberPay and the outcome is unknown. Their {@code /instant} endpoint
     * answers {@code Code: "PR"} — "your request is being processed" — so a successful call proves
     * only that they accepted it, never that the money arrived.
     */
    PROCESSING,

    /** Terminal. The money reached the creator's bank and the reserve stands. */
    PAID,

    /** Terminal. The reserve has been released by a compensating ledger pair. */
    FAILED
}
