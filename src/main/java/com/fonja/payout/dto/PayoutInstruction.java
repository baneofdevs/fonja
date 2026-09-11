package com.fonja.payout.dto;

import java.math.BigDecimal;

/**
 * One bank transfer, as the gateway needs it.
 *
 * <p>A flat value object rather than the {@code PayoutRequest} entity, so {@link
 * com.fonja.payout.service.PayoutGateway} never sees JPA — an implementation cannot accidentally
 * trip a lazy load, and it cannot mutate a payout behind the service's back.
 *
 * @param amount what the creator receives, already net of Fonja's flat fee
 * @param beneficiaryName the bank's own name for the account where one is known. Nigerian transfers
 *     are validated against it at NIBSS, so it is not decoration
 */
public record PayoutInstruction(
        String merchantReference,
        BigDecimal amount,
        String accountNumber,
        String bankCode,
        String beneficiaryName,
        String narration) {}
