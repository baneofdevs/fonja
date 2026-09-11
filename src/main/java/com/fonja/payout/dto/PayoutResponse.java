package com.fonja.payout.dto;

import com.fonja.payout.entity.PayoutRequest;
import com.fonja.payout.entity.PayoutStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One withdrawal, as the creator sees it.
 *
 * <p>Carries no gateway reference. It identifies a transaction on CyberPay's side and means nothing
 * to a creator; {@code merchantReference} is the one to quote to support, and it is ours.
 *
 * @param amount what reaches the bank
 * @param fee Fonja's flat fee, taken from the balance on top of the amount
 * @param totalDeducted {@code amount + fee} — what actually left the creator's earnings
 * @param failureReason set only on a FAILED payout, whose amount and fee have both been returned to
 *     the balance
 */
public record PayoutResponse(
        Long id,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal totalDeducted,
        PayoutStatus status,
        String merchantReference,
        String failureReason,
        Instant requestedAt,
        Instant completedAt) {

    public static PayoutResponse from(PayoutRequest request) {
        return new PayoutResponse(
                request.getId(),
                request.getAmount(),
                request.getFee(),
                request.getTotalReserved(),
                request.getStatus(),
                request.getMerchantReference(),
                request.getFailureReason(),
                request.getRequestedAt(),
                request.getCompletedAt());
    }
}
