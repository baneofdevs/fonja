package com.fonja.payout.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A creator's request to take money out, and the record of what happened to it.
 *
 * <p>The status is the whole point of this entity. {@code REQUESTED} means the ledger has already
 * reserved the money but nothing has been sent; {@code PROCESSING} means an instruction is with
 * CyberPay and its outcome is unknown; {@code PAID} and {@code FAILED} are the two terminal states,
 * and only {@code FAILED} returns the reserve to the creator's balance. Every transition below is
 * guarded, because a payout that moves backwards — {@code PAID} to {@code FAILED}, say — would
 * reverse a debit for money that actually left.
 *
 * <p>The bank account is captured on the request rather than read from the creator at settlement
 * time. A creator who relinks their account halfway through a withdrawal must not have an in-flight
 * payout silently redirected, and the record has to show where the money actually went.
 */
@Entity
@Table(name = "payout_requests")
@Getter
public class PayoutRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    /** The account this payout was aimed at, as it stood when the request was made. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_account_id")
    private BankAccount bankAccount;

    // Requested amount, before the flat withdrawal fee
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    // Flat 200 NGN, per BRD
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayoutStatus status;

    /**
     * Our reference, sent to CyberPay as {@code MerchantRef} and echoed back on their webhook. The
     * same role {@code merchant_reference} plays on a donation: it is what still identifies this
     * payout if the transaction storing CyberPay's own reference never commits.
     */
    @Column(name = "merchant_reference", nullable = false, unique = true, updatable = false)
    private String merchantReference;

    @Column(name = "gateway_reference", unique = true)
    private String gatewayReference; // nullable until CyberPay assigns one

    /** Why a FAILED payout failed, for the creator and for whoever has to explain it to them. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    /**
     * When an instruction was first sent to the gateway, set <em>before</em> the call and committed
     * on its own.
     *
     * <p>This is what stops a payout being sent twice. If the call times out we cannot tell whether
     * CyberPay accepted it, and re-sending an accepted instruction pays the creator twice with money
     * that was only reserved once. Stamping first means the automatic path takes at most one attempt
     * per payout, ever, and anything uncertain waits for a human who can check.
     */
    @Column(name = "dispatch_attempted_at")
    private Instant dispatchAttemptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PayoutRequest() {
        // JPA
    }

    public PayoutRequest(
            User creator,
            BankAccount bankAccount,
            BigDecimal amount,
            BigDecimal fee,
            String merchantReference) {
        this.creator = creator;
        this.bankAccount = bankAccount;
        this.amount = amount;
        this.fee = fee;
        this.merchantReference = merchantReference;
        this.status = PayoutStatus.REQUESTED;
    }

    /** What leaves the creator's balance: the amount they asked for plus the flat fee. */
    public BigDecimal getTotalReserved() {
        return amount.add(fee);
    }

    public boolean isTerminal() {
        return status == PayoutStatus.PAID || status == PayoutStatus.FAILED;
    }

    /**
     * Claims this payout for one automatic dispatch attempt, returning false if it has already had
     * one. Committed before the gateway is called — see {@link #dispatchAttemptedAt}.
     */
    public boolean claimForDispatch() {
        if (status != PayoutStatus.REQUESTED || dispatchAttemptedAt != null) {
            return false;
        }
        this.dispatchAttemptedAt = Instant.now();
        return true;
    }

    /**
     * Records that an instruction is now with CyberPay. From here the money may or may not have
     * moved, and only CyberPay can say which — nothing in this class may guess.
     */
    public void markProcessing(String gatewayReference) {
        requireStatus(PayoutStatus.REQUESTED, "sent to the gateway");
        this.status = PayoutStatus.PROCESSING;
        this.gatewayReference = gatewayReference;
    }

    /** Terminal, and the reserve written at request time simply stands. */
    public void markPaid() {
        requireNotTerminal("marked paid");
        this.status = PayoutStatus.PAID;
        this.completedAt = Instant.now();
    }

    /**
     * Terminal. The caller must reverse the ledger reserve — this class records the outcome and does
     * not touch balances, but a {@code FAILED} payout whose entries were never reversed is money the
     * creator has lost, so the two always happen together in {@code PayoutService}.
     */
    public void markFailed(String reason) {
        requireNotTerminal("marked failed");
        this.status = PayoutStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    private void requireStatus(PayoutStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Payout " + id + " is " + status + ", only " + expected + " may be " + action);
        }
    }

    private void requireNotTerminal(String action) {
        if (isTerminal()) {
            throw new IllegalStateException(
                    "Payout " + id + " is already " + status + " and cannot be " + action);
        }
    }
}
