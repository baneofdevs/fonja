package com.fonja.ledger.entity;

import com.fonja.donation.entity.Donation;
import com.fonja.payout.entity.PayoutRequest;
import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only. No setters, no update timestamp — rows are never modified after insert. Available
 * balance is always derived by summing entries, never stored as a mutable column. See technical
 * spec, Money-Path Rigor.
 *
 * <p>Construction goes through the static factories rather than a public constructor, because each
 * entry type has its own rules about which link is populated: donation entries carry a donation and
 * no payout request, withdrawal entries the reverse. A general-purpose constructor would let a
 * caller write a {@code WITHDRAWAL_DEBIT} hanging off a donation, which is unrepresentable in the
 * accounting but perfectly valid Java.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Null beneficiary = Fonja platform revenue entry
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "beneficiary_id")
    private User beneficiary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donation_id")
    private Donation donation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_request_id")
    private PayoutRequest payoutRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private LedgerEntryType entryType;

    // Always positive; direction is implied by entryType, not by sign.
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // JPA
    }

    private LedgerEntry(
            User beneficiary,
            Donation donation,
            PayoutRequest payoutRequest,
            LedgerEntryType entryType,
            BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            // A zero or negative entry is always a bug upstream: direction is carried by the type,
            // so a negative amount would silently invert a balance.
            throw new IllegalArgumentException(
                    "Ledger amount must be positive, got " + amount + " for " + entryType);
        }
        this.beneficiary = beneficiary;
        this.donation = donation;
        this.payoutRequest = payoutRequest;
        this.entryType = entryType;
        this.amount = amount;
    }

    /** The creator's share of a confirmed donation. */
    public static LedgerEntry donationCredit(User creator, Donation donation, BigDecimal amount) {
        return new LedgerEntry(creator, donation, null, LedgerEntryType.DONATION_CREDIT, amount);
    }

    /**
     * Fonja's share of the same donation. Deliberately has no beneficiary — see the field comment.
     */
    public static LedgerEntry platformFee(Donation donation, BigDecimal amount) {
        return new LedgerEntry(null, donation, null, LedgerEntryType.PLATFORM_FEE, amount);
    }

    /** One leg of a withdrawal: the reserved amount, the flat fee, or a reversal of either. */
    public static LedgerEntry withdrawal(
            User creator, PayoutRequest request, LedgerEntryType entryType, BigDecimal amount) {
        return new LedgerEntry(creator, null, request, entryType, amount);
    }
}
