package com.fonja.payout.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

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

    // Requested amount, before the flat withdrawal fee
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    // Flat 200 NGN, per BRD
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PayoutStatus status;

    @Column(name = "gateway_reference", unique = true)
    private String gatewayReference; // nullable until CyberPay assigns one

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected PayoutRequest() {
        // JPA
    }

    public PayoutRequest(User creator, BigDecimal amount, BigDecimal fee) {
        this.creator = creator;
        this.amount = amount;
        this.fee = fee;
        this.status = PayoutStatus.REQUESTED;
    }

    // TODO (payout module): status transitions and the CyberPay settlement call. The ledger already
    // reserves the amount at REQUESTED and reverses it on FAILED — see LedgerService.
}
