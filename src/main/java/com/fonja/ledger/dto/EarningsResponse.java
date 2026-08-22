package com.fonja.ledger.dto;

import java.math.BigDecimal;

/**
 * A creator's earnings, every figure derived by summing ledger entries at read time.
 *
 * @param available what the creator can withdraw right now — credits minus debits, with amounts
 *     already reserved by an in-flight withdrawal request excluded
 * @param totalEarned lifetime gross share of confirmed donations, net of Fonja's percentage
 * @param totalWithdrawn lifetime amount taken out, excluding withdrawals that failed and were
 *     reversed
 * @param totalWithdrawalFees lifetime flat fees paid on withdrawals, again net of reversals
 */
public record EarningsResponse(
        BigDecimal available,
        BigDecimal totalEarned,
        BigDecimal totalWithdrawn,
        BigDecimal totalWithdrawalFees) {}
