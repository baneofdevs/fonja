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

    /**
     * Our own reference, generated before CyberPay is called and never changed. Sent as {@code
     * MerchantRef} and echoed back on every webhook, which is what lets a payment be settled even
     * if we never managed to store CyberPay's own reference.
     */
    @Column(name = "merchant_reference", nullable = false, unique = true, updatable = false)
    private String merchantReference;

    /**
     * CyberPay's reference. Null until they answer the initiate call — the row has to exist before
     * that call is made. Also the idempotency key: the unique constraint is what stops a webhook
     * retry from double-processing the same transaction.
     */
    @Column(name = "gateway_reference", unique = true)
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
     * A donation starts {@link DonationStatus#PENDING} carrying only our own reference — the row is
     * committed before CyberPay is called, so a payment can never be initiated against nothing.
     */
    public Donation(
            Campaign campaign,
            User donor,
            BigDecimal amount,
            String currency,
            String merchantReference) {
        this.campaign = campaign;
        this.donor = donor;
        this.amount = amount;
        this.currency = currency;
        this.merchantReference = merchantReference;
        this.status = DonationStatus.PENDING;
    }

    /**
     * Records CyberPay's reference once they have issued one.
     *
     * <p>Write-once: re-initiating a payment that already has a reference would leave two live
     * checkout sessions against one donation row, and only one of them could ever be settled.
     */
    public void attachGatewayReference(String gatewayReference) {
        if (this.gatewayReference != null) {
            throw new IllegalStateException(
                    "Donation " + id + " already has gateway reference " + this.gatewayReference);
        }
        this.gatewayReference = gatewayReference;
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
}
