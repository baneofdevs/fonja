package com.fonja.ledger.entity;

import com.fonja.donation.entity.Donation;
import com.fonja.payout.entity.PayoutRequest;
import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Append-only. No setters, no update timestamp — rows are never modified after insert. Available
 * balance is always derived by summing entries, never stored as a mutable column. See technical
 * spec, Money-Path Rigor.
 */
@Entity
@Table(name = "ledger_entries")
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

    // Constructor-only population — intentionally no setters on this entity.
    // TODO: all-args constructor, getters per team convention
}
