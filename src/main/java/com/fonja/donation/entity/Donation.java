package com.fonja.donation.entity;

import com.fonja.campaign.entity.Campaign;
import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "donations")
@Getter
public class Donation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_id", nullable = false)
    private User donor;

    // Gross amount, before the 7% platform fee split
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    // Idempotency key — unique constraint stops a webhook retry from
    // double-processing the same CyberPay transaction.
    @Column(name = "gateway_reference", nullable = false, unique = true)
    private String gatewayReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DonationStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Donation() {
        // JPA
    }

    /**
     * A donation starts {@link DonationStatus#PENDING} — the row exists before CyberPay is called,
     * so the reference we hand them is already recorded and the call itself is safe to retry.
     */
    public Donation(
            Campaign campaign,
            User donor,
            BigDecimal amount,
            String currency,
            String gatewayReference) {
        this.campaign = campaign;
        this.donor = donor;
        this.amount = amount;
        this.currency = currency;
        this.gatewayReference = gatewayReference;
        this.status = DonationStatus.PENDING;
    }

    /**
     * Confirms a payment. Only ever called after re-querying CyberPay. A signed webhook proves who
     * sent the message, not that the money settled, so the notification alone never reaches here.
     * See technical spec §5.5.
     */
    public void markSuccessful(PaymentMethod paymentMethod) {
        this.status = DonationStatus.SUCCESS;
        this.paymentMethod = paymentMethod;
    }

    public void markFailed() {
        this.status = DonationStatus.FAILED;
    }

    // TODO (donation module): split gatewayReference into our own merchant reference, generated
    // before the CyberPay call, and theirs, filled in from the initiate response.
}
