package com.fonja.payout.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Where a creator wants their withdrawals sent.
 *
 * <p>Notably absent: the account name. It is not the creator's to assert — it is looked up from the
 * bank, and what they think the account is called has no bearing on where a transfer lands.
 *
 * @param accountNumber a NUBAN, which is always exactly ten digits
 * @param bankCode the NIP code from {@code GET /banks}, three digits. Not the old CBN sort code
 * @param bankName for display only, so a creator can recognise their own account in a list
 */
public record LinkBankAccountRequest(
        @NotBlank(message = "accountNumber is required")
                @Pattern(
                        regexp = "\\d{10}",
                        message = "accountNumber must be the 10 digits of a NUBAN")
                String accountNumber,
        @NotBlank(message = "bankCode is required")
                @Pattern(regexp = "\\d{3}", message = "bankCode must be a 3-digit NIP bank code")
                String bankCode,
        @NotBlank(message = "bankName is required") String bankName) {}
