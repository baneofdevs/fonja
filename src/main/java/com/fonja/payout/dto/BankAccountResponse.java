package com.fonja.payout.dto;

import com.fonja.payout.entity.BankAccount;
import java.time.Instant;

/**
 * A linked bank account as the creator sees it.
 *
 * @param accountNumber masked to the last four digits. The creator already knows their own account
 *     number, so showing it in full buys nothing and puts it in every log, cache and screenshot that
 *     touches this response
 * @param accountName the bank's own name for the account when it could be looked up, otherwise
 *     whatever was recorded at link time
 * @param verifiedAt when CyberPay's name enquiry confirmed the account. Null means the lookup has
 *     not succeeded — the account still works, but nothing has independently confirmed it exists
 */
public record BankAccountResponse(
        Long id,
        String accountName,
        String accountNumber,
        String bankCode,
        String bankName,
        boolean verified,
        Instant verifiedAt) {

    private static final int VISIBLE_DIGITS = 4;

    public static BankAccountResponse from(BankAccount account) {
        return new BankAccountResponse(
                account.getId(),
                account.getAccountName(),
                mask(account.getAccountNumber()),
                account.getBankCode(),
                account.getBankName(),
                account.isVerified(),
                account.getVerifiedAt());
    }

    private static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= VISIBLE_DIGITS) {
            return accountNumber;
        }
        int hidden = accountNumber.length() - VISIBLE_DIGITS;
        return "*".repeat(hidden) + accountNumber.substring(hidden);
    }
}
