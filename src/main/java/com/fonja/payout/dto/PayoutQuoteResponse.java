package com.fonja.payout.dto;

import java.math.BigDecimal;

/**
 * What a creator can actually withdraw, before they try.
 *
 * <p>Exists because {@code available} on its own is misleading. The flat fee comes out of the same
 * balance as the withdrawal, so a creator with ₦9,300 available cannot withdraw ₦9,300 — the real
 * ceiling is ₦9,100, and a client that offers "withdraw all" against {@code available} produces a
 * request that is always rejected.
 *
 * @param available the creator's balance, already net of anything an in-flight payout has reserved
 * @param withdrawalFee Fonja's flat fee, charged once per request regardless of size
 * @param maxWithdrawable {@code available - withdrawalFee}, floored at zero
 * @param canWithdraw false when {@code maxWithdrawable} is zero, or no bank account is linked
 */
public record PayoutQuoteResponse(
        BigDecimal available,
        BigDecimal withdrawalFee,
        BigDecimal maxWithdrawable,
        boolean canWithdraw,
        boolean bankAccountLinked) {}
