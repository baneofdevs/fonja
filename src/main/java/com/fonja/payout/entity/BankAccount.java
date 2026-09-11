package com.fonja.payout.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;

/**
 * Where a creator's withdrawals are sent. One per user, enforced by a unique index on {@code
 * user_id} — a creator relinking replaces this row rather than adding a second, so there is never
 * any ambiguity about which account a payout should go to.
 *
 * <p><strong>{@code accountName} is the bank's answer, not the creator's claim.</strong> When
 * CyberPay's name enquiry is reachable it returns the true account holder and that is what gets
 * stored, along with a {@code verifiedAt} stamp. The creator never gets to type a name that goes
 * anywhere near a transfer instruction.
 */
@Entity
@Table(name = "bank_accounts")
@Getter
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "bank_code", nullable = false)
    private String bankCode;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected BankAccount() {
        // JPA
    }

    public BankAccount(
            User user, String accountName, String accountNumber, String bankCode, String bankName) {
        this.user = user;
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.bankCode = bankCode;
        this.bankName = bankName;
    }

    /**
     * Points an existing link at a different account.
     *
     * <p>Clears {@code verifiedAt} unconditionally. A verification stamp belongs to the account
     * number it was obtained for, and carrying it across to a new one would mean a creator could
     * launder an unverified account through a verified link — exactly the check this stamp exists to
     * record.
     */
    public void relink(String accountName, String accountNumber, String bankCode, String bankName) {
        this.accountName = accountName;
        this.accountNumber = accountNumber;
        this.bankCode = bankCode;
        this.bankName = bankName;
        this.verifiedAt = null;
    }

    /**
     * Records that CyberPay's name enquiry confirmed this account exists and returned the holder's
     * name. Called with that name, which overwrites whatever the creator submitted.
     */
    public void markVerified(String confirmedAccountName) {
        this.accountName = confirmedAccountName;
        this.verifiedAt = Instant.now();
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }
}
