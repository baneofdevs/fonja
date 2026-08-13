package com.fonja.verification.entity;

import com.fonja.user.entity.User;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "verification_requests")
public class VerificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationType type;

    @Column(name = "registration_number")
    private String registrationNumber; // business/NGO only

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerificationStatus status;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @CreationTimestamp
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected VerificationRequest() {
        // JPA
    }

    public VerificationRequest(User user, VerificationType type, String registrationNumber) {
        this.user = user;
        this.type = type;
        this.registrationNumber = registrationNumber;
        this.status = VerificationStatus.PENDING;
    }

    public void approve(User reviewer) {
        requirePending();
        this.status = VerificationStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    public void reject(User reviewer, String reason) {
        requirePending();
        this.status = VerificationStatus.REJECTED;
        this.rejectionReason = reason;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
    }

    /**
     * A decision is final. Re-reviewing an already-decided request would let an approval be
     * silently reversed, so the entity refuses it outright — a rejected creator submits a new
     * request instead.
     */
    private void requirePending() {
        if (status != VerificationStatus.PENDING) {
            throw new IllegalStateException("Verification request " + id + " is already " + status);
        }
    }

    public boolean isPending() {
        return status == VerificationStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public VerificationType getType() {
        return type;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
